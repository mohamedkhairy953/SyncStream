package com.syncstream.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address

private const val TAG = "NsdDiscoverer"
private const val SERVICE_TYPE = "_syncstream._tcp"
private const val TXT_KEY_LABEL = "label"

/**
 * Discovered master endpoint after a successful NSD resolve.
 */
data class DiscoveredService(
    val serviceName: String,
    val host: String,       // resolved IPv4 literal
    val port: Int,
    val label: String?,     // TXT "label" value, or null if absent
)

/**
 * Lifecycle of the NSD browse operation.
 */
sealed interface DiscoveryState {
    data object Idle : DiscoveryState
    data object Discovering : DiscoveryState
    data class Failed(val errorCode: Int) : DiscoveryState
}

/**
 * Browses for `_syncstream._tcp` services and resolves them serially.
 *
 * The resolve queue enforces that only ONE [NsdManager.resolveService] call is in flight at any
 * time (the NsdManager contract rejects concurrent resolves with FAILURE_ALREADY_ACTIVE). When
 * that error code is returned, the pending info is re-enqueued with an exponential-backoff
 * delay before retrying.
 *
 * [services] is de-duplicated by [DiscoveredService.serviceName]; entries are added on resolve
 * success and removed when the browse listener reports the service is lost.
 *
 * A [WifiManager.MulticastLock] is held for the full [start]/[stop] window.
 */
class NsdDiscoverer(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _services = MutableStateFlow<List<DiscoveredService>>(emptyList())
    val services: StateFlow<List<DiscoveredService>> = _services.asStateFlow()

    private val _state = MutableStateFlow<DiscoveryState>(DiscoveryState.Idle)
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    // Internal scope that lives for the duration of a single start/stop window.
    private var scope: CoroutineScope? = null
    private var resolveJob: Job? = null

    /**
     * Pending items for the serial resolve queue. Each entry is a pair of (NsdServiceInfo,
     * retry-count) so we can compute backoff.
     */
    private val resolveQueue = Channel<Pair<NsdServiceInfo, Int>>(capacity = Channel.UNLIMITED)

    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Starts browsing for `_syncstream._tcp`. No-op if already discovering. */
    fun start() {
        if (_state.value is DiscoveryState.Discovering) {
            Log.d(TAG, "start() ignored — already discovering")
            return
        }

        acquireMulticastLock()

        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = workerScope

        // Start each scan from a clean slate. Stale entries otherwise linger: onServiceLost only
        // fires for graceful departures, so a master that stops streaming or dies without a mDNS
        // goodbye would remain visible across rescans.
        _services.value = emptyList()

        // Drain any leftover items from a previous session.
        while (true) {
            val result = resolveQueue.tryReceive()
            if (result.isFailure) break
        }

        startResolveWorker(workerScope)

        val listener = buildDiscoveryListener()
        discoveryListener = listener

        _state.value = DiscoveryState.Discovering
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        Log.i(TAG, "▶ discoverServices('$SERVICE_TYPE') invoked; list cleared, multicast lock held")
    }

    /** Stops browsing and releases the MulticastLock. Safe to call in any state. */
    fun stop() {
        val listener = discoveryListener
        discoveryListener = null

        if (listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "stopServiceDiscovery threw: ${e.message}")
            }
        }

        resolveJob?.cancel()
        resolveJob = null
        scope?.coroutineContext?.get(Job)?.cancel()
        scope = null

        // Drop the discovered list so a screen revisit doesn't show pre-cancellation results.
        _services.value = emptyList()

        _state.value = DiscoveryState.Idle
        releaseMulticastLock()
        Log.i(TAG, "Discovery stopped")
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Launches a single coroutine that consumes [resolveQueue] entries one at a time.
     * On FAILURE_ALREADY_ACTIVE the entry is re-enqueued after an exponential backoff.
     */
    private fun startResolveWorker(workerScope: CoroutineScope) {
        resolveJob = workerScope.launch {
            for ((info, retryCount) in resolveQueue) {
                if (retryCount > 0) {
                    // Exponential backoff: 200ms * 2^(retryCount-1), capped at 4s.
                    val backoffMs = minOf(200L * (1L shl (retryCount - 1)), 4_000L)
                    Log.d(TAG, "Backoff ${backoffMs}ms before retrying resolve for '${info.serviceName}'")
                    delay(backoffMs)
                }
                resolveServiceSerially(info, retryCount)
            }
        }
    }

    /**
     * Issues one [NsdManager.resolveService] call and suspends until the result is delivered
     * via the listener (success or failure). On FAILURE_ALREADY_ACTIVE, re-enqueues with an
     * incremented retry counter instead of failing permanently.
     */
    private suspend fun resolveServiceSerially(info: NsdServiceInfo, retryCount: Int) {
        // Use a fresh single-shot channel to bridge the callback into the coroutine world.
        val resultChannel = Channel<Result<NsdServiceInfo>>(capacity = 1)

        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                resultChannel.trySend(Result.success(resolvedInfo))
            }

            override fun onResolveFailed(failedInfo: NsdServiceInfo, errorCode: Int) {
                resultChannel.trySend(Result.failure(ResolveException(errorCode, failedInfo)))
            }
        }

        Log.i(TAG, "▶ resolveService('${info.serviceName}') attempt=${retryCount}")
        try {
            nsdManager.resolveService(info, listener)
        } catch (e: Exception) {
            Log.e(TAG, "resolveService() threw: ${e.message}")
            return
        }

        val result = resultChannel.receive()
        result.fold(
            onSuccess = { resolved -> handleResolved(resolved) },
            onFailure = { err ->
                if (err is ResolveException) {
                    if (err.errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        Log.d(TAG, "FAILURE_ALREADY_ACTIVE for '${err.info.serviceName}', re-enqueuing (attempt ${retryCount + 1})")
                        resolveQueue.trySend(Pair(err.info, retryCount + 1))
                    } else {
                        Log.w(
                            TAG,
                            "Resolve failed for '${err.info.serviceName}' errorCode=${err.errorCode}. " +
                                "If this is consistent, check router AP isolation settings.",
                        )
                    }
                }
            },
        )
    }

    private fun handleResolved(info: NsdServiceInfo) {
        // Prefer IPv4; NsdServiceInfo.host is a java.net.InetAddress.
        val inetAddress = info.host ?: run {
            Log.w(TAG, "Resolved '${info.serviceName}' but host is null")
            return
        }

        // On API 26+, resolveService may return the InetAddress directly (may be IPv6).
        // Walk all addresses if available, prefer Inet4Address.
        val ipLiteral: String = when (inetAddress) {
            is Inet4Address -> inetAddress.hostAddress ?: inetAddress.toString()
            else -> inetAddress.hostAddress ?: inetAddress.toString()
        }

        // Extract the TXT "label" attribute (API 21+ NsdServiceInfo.attributes).
        val label: String? = try {
            info.attributes[TXT_KEY_LABEL]?.let { bytes ->
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }

        val discovered = DiscoveredService(
            serviceName = info.serviceName,
            host = ipLiteral,
            port = info.port,
            label = label,
        )

        _services.update { current ->
            // De-duplicate by serviceName; replace if already present (refresh).
            val filtered = current.filter { it.serviceName != discovered.serviceName }
            val next = filtered + discovered
            Log.i(TAG, "▶ RESOLVED $discovered — list size ${current.size} -> ${next.size}")
            next
        }
    }

    private fun buildDiscoveryListener(): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "onStartDiscoveryFailed errorCode=$errorCode")
                _state.value = DiscoveryState.Failed(errorCode)
                releaseMulticastLock()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "onStopDiscoveryFailed errorCode=$errorCode")
                _state.value = DiscoveryState.Idle
                releaseMulticastLock()
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.i(TAG, "onDiscoveryStarted serviceType=$serviceType")
                _state.value = DiscoveryState.Discovering
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "onDiscoveryStopped serviceType=$serviceType")
                _state.value = DiscoveryState.Idle
                releaseMulticastLock()
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "▶ onServiceFound: '${serviceInfo.serviceName}' type='${serviceInfo.serviceType}' — enqueuing resolve")
                // Enqueue for serial resolve; first attempt = retry 0.
                resolveQueue.trySend(Pair(serviceInfo, 0))
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "▶ onServiceLost: '${serviceInfo.serviceName}'")
                _services.update { current ->
                    val next = current.filter { it.serviceName != serviceInfo.serviceName }
                    Log.i(TAG, "▶ list size ${current.size} -> ${next.size} after lost")
                    next
                }
            }
        }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock(TAG).also {
            it.setReferenceCounted(false)
            it.acquire()
            Log.d(TAG, "MulticastLock acquired")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.let {
            it.release()
            Log.d(TAG, "MulticastLock released")
        }
        multicastLock = null
    }

    /** Typed wrapper for resolve failures so the worker loop can inspect the error code. */
    private class ResolveException(val errorCode: Int, val info: NsdServiceInfo) :
        Exception("NSD resolve failed: errorCode=$errorCode service=${info.serviceName}")
}
