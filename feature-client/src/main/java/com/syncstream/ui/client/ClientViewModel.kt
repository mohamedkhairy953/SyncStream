package com.syncstream.ui.client

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syncstream.appContainer
import com.syncstream.discovery.DiscoveredService
import com.syncstream.discovery.DiscoveryState
import com.syncstream.discovery.NsdDiscoverer
import com.syncstream.signaling.ConnectionState
import com.syncstream.core.PeerState
import com.syncstream.signaling.SignalingClient
import com.syncstream.sync.ClockSync
import com.syncstream.sync.PlayheadInfo
import com.syncstream.sync.SyncEngine
import com.syncstream.sync.AudioReceiver
import com.syncstream.webrtc.ClientPeerManager
import com.syncstream.webrtc.WebRtcCore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.VideoTrack

/**
 * Client-side composition root. Owns (no service) the [SignalingClient], a [ClientPeerManager]
 * fed by the shared [WebRtcCore] (EGL/factory from [com.syncstream.AppContainer]), a [ClockSync],
 * a client-side [SyncEngine] and an [AudioReceiver], plus the [NsdDiscoverer].
 *
 * The ViewModel holds NO raw signalling/WebRTC state in the UI sense — it exposes only the
 * derived [StateFlow]s the screens render. WebRTC objects are created/destroyed in [connect]/
 * [disconnect]; the EglBase + PeerConnectionFactory remain app-scoped and are never released.
 */
class ClientViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.appContainer

    /** Shared EGL context for the client's `SurfaceViewRenderer`. */
    val eglContext: EglBase.Context get() = container.eglBase.eglBaseContext

    // ---- Discovery ----
    private val discoverer = NsdDiscoverer(app)
    val discovered: StateFlow<List<DiscoveredService>> get() = discoverer.services
    val discoveryState: StateFlow<DiscoveryState> get() = discoverer.state

    // ---- Connection / peer / playback ----
    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _peerState = MutableStateFlow(PeerState.NEW)
    val peerState: StateFlow<PeerState> = _peerState.asStateFlow()

    private val _playhead = MutableStateFlow(PlayheadInfo(playing = false, positionMs = 0, durationMs = 0))
    val playhead: StateFlow<PlayheadInfo> = _playhead.asStateFlow()

    private val _muted = MutableStateFlow(true)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _latencyBadgeMs = MutableStateFlow(0L)
    val latencyBadgeMs: StateFlow<Long> = _latencyBadgeMs.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    // ---- Runtime objects, alive only while connected ----
    private var core: WebRtcCore? = null
    private var signaling: SignalingClient? = null
    private var peerManager: ClientPeerManager? = null
    private var clock: ClockSync? = null
    private var syncEngine: SyncEngine? = null
    private var audioReceiver: AudioReceiver? = null

    private val collectors = mutableListOf<Job>()
    private var statsJob: Job? = null

    /** The last sessionId the master issued us; re-presented on reconnect to reclaim our slot. */
    @Volatile
    private var lastSessionId: String? = null

    fun startDiscovery() = discoverer.start()
    fun stopDiscovery() = discoverer.stop()

    /**
     * Builds the full client runtime and dials the master. Any prior runtime is torn down first.
     * [previousSessionId] (or the cached [lastSessionId]) lets the master reclaim our encoder slot.
     */
    fun connect(host: String, port: Int, pin: String, previousSessionId: String? = null) {
        teardownRuntime()

        val webRtc = WebRtcCore(container.peerConnectionFactory, container.eglBase)
        core = webRtc

        val clk = ClockSync(viewModelScope)
        clock = clk

        val sync = SyncEngine(viewModelScope)
        syncEngine = sync
        collectors += viewModelScope.launch { sync.clientPlayhead.collect { _playhead.value = it } }

        val receiver = AudioReceiver(clk)
        audioReceiver = receiver
        receiver.setMuted(_muted.value)
        receiver.start()
        collectors += viewModelScope.launch { receiver.muted.collect { _muted.value = it } }

        val peers = ClientPeerManager(
            core = webRtc,
            callbacks = peerCallbacks(sync, clk, receiver),
            scope = viewModelScope,
        )
        peerManager = peers

        val client = SignalingClient(
            host = host,
            port = port,
            pin = pin,
            deviceName = deviceName(),
            callbacks = signalingCallbacks(peers),
            scope = viewModelScope,
        )
        signaling = client
        collectors += viewModelScope.launch {
            client.connection.collect { state ->
                _connection.value = state
                if (state is ConnectionState.Connected) lastSessionId = state.sessionId
            }
        }

        client.connect(previousSessionId ?: lastSessionId)
        startStatsPolling(clk, peers)
    }

    /** Tears down the live session and returns the UI to a disconnected baseline. */
    fun disconnect() {
        signaling?.disconnect("client left")
        teardownRuntime()
        _connection.value = ConnectionState.Disconnected
        _peerState.value = PeerState.CLOSED
        _remoteVideoTrack.value = null
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        audioReceiver?.setMuted(muted)
    }

    // ---- Wiring ----

    private fun signalingCallbacks(peers: ClientPeerManager) = object : SignalingClient.Callbacks {
        override fun onWelcome(sessionId: String, masterName: String) {
            lastSessionId = sessionId
        }

        override fun onOffer(sdp: String) {
            peers.onRemoteOffer(sdp)
        }

        override fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
            peers.onRemoteIce(sdpMid, sdpMLineIndex, candidate)
        }

        override fun onClosed(reason: String?) {
            // Master ended or socket dropped: surface as CLOSED so the UI can show the terminal state.
            _peerState.value = PeerState.CLOSED
        }

        override fun onError(t: Throwable) {
            // Errors are reflected through connection state; nothing extra to surface here.
        }
    }

    private fun peerCallbacks(
        sync: SyncEngine,
        clk: ClockSync,
        receiver: AudioReceiver,
    ) = object : ClientPeerManager.Callbacks {
        override suspend fun onLocalIce(c: IceCandidate) {
            signaling?.sendIce(c.sdpMid, c.sdpMLineIndex, c.sdp)
        }

        override suspend fun onLocalAnswer(sdp: String) {
            signaling?.sendAnswer(sdp)
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            _remoteVideoTrack.value = track
        }

        override fun onCmdChannel(channel: DataChannel) {
            sync.attachClientCmdChannel(channel, clk)
        }

        override fun onPingChannel(channel: DataChannel) {
            clk.attachClientChannel(channel)
        }

        override fun onAudioChannel(channel: DataChannel) {
            receiver.attachAudioChannel(channel)
        }

        override fun onPeerStateChanged(state: PeerState) {
            _peerState.value = state
        }
    }

    /**
     * Produces the latency badge every 2s: one-way network estimate (`rtt / 2` from [ClockSync])
     * plus the current video jitter-buffer delay, delta-sampled as
     * `ΔjitterBufferDelay / ΔjitterBufferEmittedCount` between polls — the raw ratio is a
     * cumulative average since connection start, so only the delta reflects the live buffer.
     * Excludes decode/render time, so it slightly underestimates true glass-to-glass.
     */
    private fun startStatsPolling(clk: ClockSync, peers: ClientPeerManager) {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            var lastDelaySec = 0.0
            var lastEmitted = 0L
            while (isActive) {
                delay(STATS_POLL_MS)
                val oneWayMs = (clk.rttMs.value / 2).coerceAtLeast(0)
                var jitterBufferMs = 0L
                val inbound = peers.getInboundStats()?.statsMap?.values?.firstOrNull {
                    it.type == "inbound-rtp" && it.members["kind"] == "video"
                }
                if (inbound != null) {
                    val delaySec = (inbound.members["jitterBufferDelay"] as? Number)?.toDouble() ?: 0.0
                    val emitted = (inbound.members["jitterBufferEmittedCount"] as? Number)?.toLong() ?: 0L
                    val dDelay = delaySec - lastDelaySec
                    val dEmitted = emitted - lastEmitted
                    if (dEmitted > 0 && dDelay >= 0.0) {
                        jitterBufferMs = (dDelay / dEmitted * 1000.0).toLong()
                    }
                    lastDelaySec = delaySec
                    lastEmitted = emitted
                }
                _latencyBadgeMs.value = oneWayMs + jitterBufferMs
            }
        }
    }

    private fun teardownRuntime() {
        statsJob?.cancel()
        statsJob = null
        collectors.forEach { it.cancel() }
        collectors.clear()

        audioReceiver?.stop()
        audioReceiver = null
        clock?.detach()
        clock = null
        syncEngine = null
        peerManager?.close()
        peerManager = null
        signaling = null
        core = null
        _remoteVideoTrack.value = null
    }

    override fun onCleared() {
        signaling?.disconnect("client closed")
        teardownRuntime()
        discoverer.stop()
        super.onCleared()
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private companion object {
        const val STATS_POLL_MS = 2_000L
    }
}
