package com.syncstream.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "NsdAdvertiser"
private const val SERVICE_TYPE = "_syncstream._tcp"
private const val TXT_KEY_LABEL = "label"

/**
 * State describing the current NSD advertisement lifecycle.
 */
sealed interface AdvertiseState {
    data object Idle : AdvertiseState
    data object Registering : AdvertiseState
    data class Registered(val serviceName: String) : AdvertiseState
    data class Failed(val errorCode: Int) : AdvertiseState
}

/**
 * Advertises a `_syncstream._tcp` mDNS service on the given port with a `label` TXT record.
 *
 * Idempotent: calling [register] while already [AdvertiseState.Registering] or
 * [AdvertiseState.Registered] is a no-op. Holds a [WifiManager.MulticastLock] for the full
 * duration between [register] and [unregister].
 */
class NsdAdvertiser(private val context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _state = MutableStateFlow<AdvertiseState>(AdvertiseState.Idle)
    val state: StateFlow<AdvertiseState> = _state.asStateFlow()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * Registers the service. No-op if registration is already in progress or complete.
     *
     * @param port the TCP port of the Ktor signaling server
     * @param sessionLabel human-readable label carried in the `label` TXT record
     */
    fun register(port: Int, sessionLabel: String) {
        val currentState = _state.value
        if (currentState is AdvertiseState.Registering || currentState is AdvertiseState.Registered) {
            Log.d(TAG, "register() ignored — already in state $currentState")
            return
        }

        acquireMulticastLock()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "SyncStream"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(TXT_KEY_LABEL, sessionLabel)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(registeredInfo: NsdServiceInfo) {
                // NsdManager may auto-rename to avoid conflicts; use the returned name.
                val name = registeredInfo.serviceName
                Log.i(TAG, "▶ onServiceRegistered as '$name' — now advertising on the network")
                _state.value = AdvertiseState.Registered(name)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed, errorCode=$errorCode")
                _state.value = AdvertiseState.Failed(errorCode)
                releaseMulticastLock()
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Service unregistered: ${serviceInfo.serviceName}")
                _state.value = AdvertiseState.Idle
                releaseMulticastLock()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed, errorCode=$errorCode")
                // Still transition to Idle so callers can retry.
                _state.value = AdvertiseState.Idle
                releaseMulticastLock()
            }
        }

        registrationListener = listener
        _state.value = AdvertiseState.Registering
        Log.i(TAG, "▶ registerService('SyncStream', type='$SERVICE_TYPE', port=$port, label='$sessionLabel')")
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    /**
     * Stops advertising. Safe to call in any state. The [AdvertiseState.Idle] transition and
     * MulticastLock release happen inside the [NsdManager.RegistrationListener] callbacks.
     */
    fun unregister() {
        val listener = registrationListener ?: run {
            Log.d(TAG, "unregister() — no active listener, resetting state")
            _state.value = AdvertiseState.Idle
            releaseMulticastLock()
            return
        }
        try {
            nsdManager.unregisterService(listener)
        } catch (e: IllegalArgumentException) {
            // Listener was never registered or already unregistered.
            Log.w(TAG, "unregisterService threw IllegalArgumentException: ${e.message}")
            _state.value = AdvertiseState.Idle
            releaseMulticastLock()
        } finally {
            registrationListener = null
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
}
