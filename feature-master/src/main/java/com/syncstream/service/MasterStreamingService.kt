package com.syncstream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.getSystemService
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.syncstream.appContainer
import com.syncstream.discovery.AdvertiseState
import com.syncstream.discovery.NsdAdvertiser
import com.syncstream.player.MasterPlayerController
import com.syncstream.player.PcmTapProcessor
import com.syncstream.player.PlaybackInfo
import com.syncstream.webrtc.ExoSurfaceVideoSource
import com.syncstream.signaling.ClientHandle
import com.syncstream.signaling.ClientRegistry
import com.syncstream.core.PeerState
import com.syncstream.signaling.SignalingServer
import com.syncstream.sync.AudioStreamer
import com.syncstream.sync.ClockSync
import com.syncstream.sync.SyncEngine
import com.syncstream.webrtc.MasterPeerManager
import com.syncstream.webrtc.WebRtcCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import java.security.SecureRandom

/**
 * Foreground service (type mediaPlayback) that owns ALL master runtime state: NSD advertising,
 * the Ktor signaling server, the WebRTC peer fan-out, the ExoPlayer + MediaSession, clock sync,
 * the sync engine, and PCM audio streaming. The Activity binds via [LocalBinder] and only
 * observes the exposed [StateFlow]s — it never reaches into any core object.
 *
 * A media3 [androidx.media3.session.MediaSession] is attached to the ExoPlayer to legitimize the
 * FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK type. PARTIAL_WAKE_LOCK + a WifiLock are held for the
 * streaming duration. Teardown happens in strict reverse order in [onDestroy] / [stopStreaming].
 */
class MasterStreamingService : LifecycleService() {

    /** Binder handing the Activity an observe-only reference to this service. */
    inner class LocalBinder : Binder() {
        fun service(): MasterStreamingService = this@MasterStreamingService
    }

    private val binder = LocalBinder()

    // ---- App-scoped primitives (never released here) ----
    private val container by lazy { applicationContext.appContainer }

    /** Service-scoped supervisor; cancelled on teardown. Distinct from the app scope. */
    private val serviceScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + container.appScope.coroutineContext)
    }

    // ---- Core objects, created on startStreaming ----
    private var webRtcCore: WebRtcCore? = null
    private var videoSource: ExoSurfaceVideoSource? = null
    private var playerController: MasterPlayerController? = null
    private var pcmTap: PcmTapProcessor? = null
    private var signalingServer: SignalingServer? = null
    private var advertiser: NsdAdvertiser? = null
    private var peerManager: MasterPeerManager? = null
    private var syncEngine: SyncEngine? = null
    private var audioStreamer: AudioStreamer? = null
    private val registry = ClientRegistry(maxClients = MAX_CLIENTS)

    /** Per-client master-side clock responders keyed by sessionId. */
    private val masterClocks = mutableMapOf<String, ClockSync>()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var heartbeatJob: Job? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    private var started = false

    // ---- Observable state for MasterScreen ----
    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    private val _sessionLabel = MutableStateFlow("")
    val sessionLabel: StateFlow<String> = _sessionLabel.asStateFlow()

    val clients: StateFlow<List<ClientHandle>> get() = registry.clients

    private val _playback = MutableStateFlow(PlaybackInfo(playing = false, positionMs = 0, durationMs = 0, ended = false))
    val playback: StateFlow<PlaybackInfo> = _playback.asStateFlow()

    private val _advertise = MutableStateFlow<AdvertiseState>(AdvertiseState.Idle)
    val advertise: StateFlow<AdvertiseState> = _advertise.asStateFlow()

    private val _thermalWarning = MutableStateFlow(false)
    val thermalWarning: StateFlow<Boolean> = _thermalWarning.asStateFlow()

    private val _notificationsDenied = MutableStateFlow(false)
    val notificationsDenied: StateFlow<Boolean> = _notificationsDenied.asStateFlow()

    /** Exposes the shared EglBase context for UI renderers and the preview sink wiring. */
    fun eglContextProvider() = webRtcCore?.eglBase
    fun videoSourceProvider(): ExoSurfaceVideoSource? = videoSource

    private var pendingUri: Uri? = null

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_MEDIA_URI, Uri::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_MEDIA_URI)
                }
                if (uri != null) pendingUri = uri
                startStreaming()
            }
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    // ---- Commands from UI ----

    fun setMediaUri(uri: Uri) {
        pendingUri = uri
        playerController?.setMediaUri(uri)
    }

    /**
     * Brings the service to the foreground, composes the runtime in dependency order, registers
     * NSD, starts Ktor, and acquires the wake/wifi locks. Idempotent.
     */
    fun startStreaming() {
        if (started) {
            pendingUri?.let { playerController?.setMediaUri(it) }
            return
        }
        started = true

        // Foreground notification first (FGS requirement). POST_NOTIFICATIONS denial only affects
        // visibility on API 33+, not the ability to run; surface it for the in-app banner.
        ensureNotificationChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
        } catch (t: Throwable) {
            // SecurityException on missing notification permission etc.: keep running headless.
            _notificationsDenied.value = true
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            _notificationsDenied.value = !granted
        }

        // 4-digit PIN + label.
        _pin.value = generatePin()
        _sessionLabel.value = buildSessionLabel()

        acquireLocks()

        // Creation order (per plan): WebRtcCore -> ExoSurfaceVideoSource -> MasterPlayerController
        // -> SignalingServer -> NsdAdvertiser -> SyncEngine -> MasterPeerManager.
        val core = WebRtcCore(container.peerConnectionFactory, container.eglBase)
        webRtcCore = core

        val vs = ExoSurfaceVideoSource(core)
        videoSource = vs

        val tap = PcmTapProcessor()
        pcmTap = tap

        val player = MasterPlayerController(this, vs, tap)
        playerController = player

        val audio = AudioStreamer()
        audioStreamer = audio
        tap.setOnChunk { pcm, masterClockMs -> audio.onPcmChunk(pcm, masterClockMs) }

        val sync = SyncEngine(serviceScope)
        syncEngine = sync

        val peers = MasterPeerManager(
            core = core,
            sharedTrack = vs.videoTrack,
            callbacks = peerCallbacks,
            scope = serviceScope,
        )
        peerManager = peers

        val server = SignalingServer(
            pin = _pin.value,
            masterName = masterName(),
            registry = registry,
            callbacks = signalingCallbacks,
            scope = serviceScope,
        )
        signalingServer = server
        val boundPort = server.start(PREFERRED_PORT)

        val nsd = NsdAdvertiser(applicationContext)
        advertiser = nsd
        nsd.register(boundPort, _sessionLabel.value)

        registerThermalListener()
        observePlayback()

        pendingUri?.let { player.setMediaUri(it) }

        // 1s State heartbeat broadcast to all clients.
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val pc = playerController ?: continue
                // ExoPlayer may only be touched on the main thread (it was built there). Snapshot
                // the live playback state on main, then broadcast off it.
                val snapshot = withContext(Dispatchers.Main) {
                    PlaybackInfo(
                        playing = pc.isPlaying(),
                        positionMs = pc.currentPositionMs(),
                        durationMs = pc.durationMs(),
                        ended = false,
                    )
                }
                syncEngine?.broadcastState(
                    playing = snapshot.playing,
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                )
            }
        }
    }

    fun play() {
        val pc = playerController ?: return
        pc.play()
        syncEngine?.broadcastPlay(pc.currentPositionMs())
    }

    fun pause() {
        val pc = playerController ?: return
        pc.pause()
        syncEngine?.broadcastPause(pc.currentPositionMs())
    }

    fun seekTo(positionMs: Long) {
        playerController?.seekTo(positionMs)
        syncEngine?.broadcastSeek(positionMs)
    }

    fun setLoop(enabled: Boolean) {
        playerController?.setLoop(enabled)
    }

    /** Tears down all runtime in strict reverse order. App-scoped EGL/factory persist. */
    fun stopStreaming() {
        if (!started) return
        started = false

        heartbeatJob?.cancel()
        heartbeatJob = null

        // 1. NSD unregister.
        advertiser?.unregister()
        advertiser = null

        // 2. Bye to all clients (best-effort) + 3. close DataChannels + 4. pc.close() each.
        val server = signalingServer
        registry.forEach { handle ->
            serviceScope.launch { runCatching { server?.sendBye(handle.sessionId, "master ended") } }
            syncEngine?.unregisterCmdChannel(handle.sessionId)
            audioStreamer?.unregisterAudioChannel(handle.sessionId)
            masterClocks.remove(handle.sessionId)?.detach()
        }
        peerManager?.closeAll()
        peerManager = null

        // 5. Stop Ktor.
        signalingServer?.stop()
        signalingServer = null

        // 6. player.release().
        playerController?.release()
        playerController = null
        pcmTap?.setOnChunk(null)
        pcmTap = null
        audioStreamer?.reset()
        audioStreamer = null
        syncEngine = null

        // 7. videoSource.release().
        videoSource?.release()
        videoSource = null
        webRtcCore = null

        unregisterThermalListener()
        releaseLocks()

        masterClocks.values.forEach { it.detach() }
        masterClocks.clear()

        _advertise.value = AdvertiseState.Idle
        _thermalWarning.value = false

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopStreaming()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- Wiring: SignalingServer.Callbacks -> MasterPeerManager / registry ----

    private val signalingCallbacks = object : SignalingServer.Callbacks {
        override fun onClientHello(sessionId: String, deviceName: String, isReconnect: Boolean): Boolean {
            if (isReconnect) {
                // Reclaim the encoder slot: tear down the stale peer before re-adding.
                peerManager?.removePeer(sessionId)
                syncEngine?.unregisterCmdChannel(sessionId)
                audioStreamer?.unregisterAudioChannel(sessionId)
                masterClocks.remove(sessionId)?.detach()
            }
            if (!isReconnect && !registry.hasCapacity()) return false
            // Create the outbound peer (pc, channels, transceiver) and begin OFFERING.
            peerManager?.addPeer(sessionId)
            return true
        }

        override fun onAnswer(sessionId: String, sdp: String) {
            peerManager?.onRemoteAnswer(sessionId, sdp)
        }

        override fun onRemoteIce(sessionId: String, sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
            peerManager?.onRemoteIce(sessionId, sdpMid, sdpMLineIndex, candidate)
        }

        override fun onClientGone(sessionId: String, reason: String?) {
            peerManager?.removePeer(sessionId)
            syncEngine?.unregisterCmdChannel(sessionId)
            audioStreamer?.unregisterAudioChannel(sessionId)
            masterClocks.remove(sessionId)?.detach()
            registry.remove(sessionId)
        }
    }

    // ---- Wiring: MasterPeerManager.Callbacks -> SignalingServer + sync/clock/audio ----

    private val peerCallbacks = object : MasterPeerManager.Callbacks {
        override suspend fun onLocalIce(sessionId: String, c: IceCandidate) {
            signalingServer?.sendIce(sessionId, c.sdpMid, c.sdpMLineIndex, c.sdp)
        }

        override suspend fun onLocalOffer(sessionId: String, sdp: String) {
            signalingServer?.sendOffer(sessionId, sdp)
        }

        override fun onPeerStateChanged(sessionId: String, state: PeerState) {
            // FSM transitions are reflected in ClientRegistry via the per-client ClientFsm;
            // nothing extra needed here beyond surfacing for logging.
        }

        override fun onCmdChannelOpen(sessionId: String, channel: DataChannel) {
            syncEngine?.registerCmdChannel(sessionId, channel)
        }

        override fun onPingChannelOpen(sessionId: String, channel: DataChannel) {
            val clock = ClockSync(serviceScope)
            clock.attachMasterChannel(channel)
            masterClocks[sessionId] = clock
        }

        override fun onAudioChannelOpen(sessionId: String, channel: DataChannel) {
            audioStreamer?.registerAudioChannel(sessionId, channel)
        }
    }

    // ---- Playback observation ----

    private fun observePlayback() {
        val pc = playerController ?: return
        serviceScope.launch {
            pc.playback.collect { info ->
                _playback.value = info
                if (info.ended) {
                    syncEngine?.broadcastEnd()
                }
            }
        }
        val nsd = advertiser ?: return
        serviceScope.launch { nsd.state.collect { _advertise.value = it } }
    }

    // ---- Thermal ----

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = getSystemService<PowerManager>() ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                videoSource?.adaptOutputFormat(960, 540, 24)
                _thermalWarning.value = true
            }
        }
        thermalListener = listener
        pm.addThermalStatusListener(listener)
    }

    private fun unregisterThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val pm = getSystemService<PowerManager>() ?: return
        thermalListener?.let { pm.removeThermalStatusListener(it) }
        thermalListener = null
    }

    // ---- Locks ----

    private fun acquireLocks() {
        val pm = getSystemService<PowerManager>()
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifi?.createWifiLock(mode, WIFILOCK_TAG)?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    // ---- Notification ----

    private fun ensureNotificationChannel() {
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SyncStream Master",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Streaming session is active" }
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MasterStreamingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncStream — streaming")
            .setContentText("PIN ${_pin.value} • ${registry.count()} client(s)")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    // ---- Helpers ----

    private fun masterName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private fun buildSessionLabel(): String = "SyncStream • ${Build.MODEL}"

    private fun generatePin(): String {
        val n = SecureRandom().nextInt(10_000)
        return n.toString().padStart(4, '0')
    }

    companion object {
        const val ACTION_START = "com.syncstream.action.START"
        const val ACTION_STOP = "com.syncstream.action.STOP"
        const val EXTRA_MEDIA_URI = "extra_media_uri"

        private const val MAX_CLIENTS = 4
        private const val PREFERRED_PORT = 0 // 0 -> OS picks a free ephemeral port
        private const val HEARTBEAT_MS = 1_000L
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000 // 6h safety cap
        private const val CHANNEL_ID = "syncstream_master"
        private const val NOTIFICATION_ID = 1001
        private const val WAKELOCK_TAG = "SyncStream::MasterWakeLock"
        private const val WIFILOCK_TAG = "SyncStream::MasterWifiLock"
    }
}
