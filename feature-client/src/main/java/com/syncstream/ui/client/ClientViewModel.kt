package com.syncstream.ui.client

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.VideoTrack

/**
 * Client-side composition root. Owns (no service) the [SignalingClient], a [ClientPeerManager]
 * fed by the shared [WebRtcCore] (EGL/factory from [com.syncstream.AppContainer]), a [ClockSync],
 * a client-side [SyncEngine] and an [AudioReceiver], plus the [NsdDiscoverer]. One instance is
 * shared by the guest screens (Join code, Scan, Watch). A room is joined either by code
 * ([joinWithCode], which tries the code against one chosen room or every discovered one) or
 * directly with a PIN already known to be right ([joinDirect], from a QR or the manual
 * `host:port:pin` dialog) — the latter matters for cameraless devices such as Android TV.
 *
 * The ViewModel holds NO raw signalling/WebRTC state in the UI sense — it exposes only the
 * derived [StateFlow]s the screens render. WebRTC objects are created/destroyed in [connect]/
 * [disconnect]; the EglBase + PeerConnectionFactory remain app-scoped and are never released.
 */
class ClientViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app.appContainer

    private val wifiManager = app.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Held for the whole connected session (not just discovery). WebRTC obfuscates host ICE
     * candidates as `.local` mDNS names; resolving the master's candidate requires receiving
     * multicast mDNS responses, which Android only delivers while a MulticastLock is held. The
     * NsdDiscoverer lock is released the moment we connect, so without this the ICE handshake
     * stalls and no media ever flows — most visibly on a hotspot with no internet.
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    /** Shared EGL context for the client's `SurfaceViewRenderer`. */
    val eglContext: EglBase.Context get() = container.eglBase.eglBaseContext

    // ---- Discovery (browse the LAN for masters; used by the "no room chosen" code path) ----
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

    // ---- Joining ----
    private val _joinStatus = MutableStateFlow<JoinStatus>(JoinStatus.Idle)
    val joinStatus: StateFlow<JoinStatus> = _joinStatus.asStateFlow()

    /**
     * The room this VM is connected (or connecting) to; its label is shown on the watch screen.
     * Rooms joined by QR / manual address start labelled with their IP; the master's name from
     * `Welcome` replaces that once it arrives.
     */
    private val _currentRoom = MutableStateFlow<RoomTarget?>(null)
    val currentRoom: StateFlow<RoomTarget?> = _currentRoom.asStateFlow()

    /** The in-flight [joinWithCode] matcher, cancelled by [cancelJoin] / [joinDirect]. */
    private var joinJob: Job? = null

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

    /** host:port of the last dial; a session id is only re-presented to the same master. */
    private var lastDialed: Pair<String, Int>? = null

    /**
     * Set when the master said Bye (room ended or refused). Keeps [peerState] at CLOSED so a late
     * ICE state change from the dying peer connection can't flip the terminal UI to "Reconnecting".
     */
    @Volatile
    private var roomEnded = false

    fun startDiscovery() = discoverer.start()
    fun stopDiscovery() = discoverer.stop()

    /** Code entry: try [code] against [rooms] (a single chosen room, or every discovered one). */
    fun joinWithCode(code: String, rooms: List<RoomTarget>) {
        if (_joinStatus.value is JoinStatus.Trying) return
        _joinStatus.value = JoinStatus.Trying
        joinJob = viewModelScope.launch {
            val result = RoomCodeMatcher.match(rooms) { room -> attempt(room, code) }
            if (result !is MatchResult.Joined) {
                disconnect()
                _currentRoom.value = null
            }
            _joinStatus.value = JoinStatus.Done(result)
        }
    }

    /**
     * Abandons an in-flight [joinWithCode] (the guest backed out of Join code). A half-made
     * attempt is torn down; an already-accepted session is left alone.
     */
    fun cancelJoin() {
        val wasJoining = joinJob?.isActive == true
        joinJob?.cancel()
        joinJob = null
        _joinStatus.value = JoinStatus.Idle
        if (wasJoining && _connection.value !is ConnectionState.Connected) {
            disconnect()
            _currentRoom.value = null
        }
    }

    fun resetJoinStatus() {
        _joinStatus.value = JoinStatus.Idle
    }

    /** QR / IP-fallback path: the PIN is already known to be the right one for this room. */
    fun joinDirect(room: RoomTarget, pin: String) {
        // A code match still running must not adopt (or tear down) this session.
        joinJob?.cancel()
        joinJob = null
        _joinStatus.value = JoinStatus.Idle
        _currentRoom.value = room
        connect(room.host, room.port, pin)
    }

    /**
     * Dials [room] with [code] and waits for the master's verdict. [connect] resets [connection]
     * to `Connecting` first, so a `Connected` left over from a previous room can't satisfy the
     * wait. A room that never answers within [ATTEMPT_TIMEOUT_MS] is reported as unreachable; its
     * retry loop is stopped by the next [connect] (or by [disconnect] once matching ends).
     */
    private suspend fun attempt(room: RoomTarget, code: String): AttemptResult {
        _currentRoom.value = room
        connect(room.host, room.port, code)
        val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
            connection.first { it is ConnectionState.Connected || it is ConnectionState.Rejected }
        }
        return when (outcome) {
            is ConnectionState.Connected -> AttemptResult.Accepted
            is ConnectionState.Rejected -> RoomCodeMatcher.classifyRejection(outcome.reason)
            else -> AttemptResult.Unreachable
        }
    }

    /**
     * Builds the full client runtime and dials the master. Any prior runtime is torn down first.
     * [previousSessionId] (or the cached [lastSessionId]) lets the master reclaim our encoder slot.
     */
    fun connect(host: String, port: Int, pin: String, previousSessionId: String? = null) {
        teardownRuntime()
        // Never let a stale Connected/Rejected from a previous room be observed for this dial.
        _connection.value = ConnectionState.Connecting
        _peerState.value = PeerState.NEW
        roomEnded = false
        // Don't present room A's session id to room B.
        if (lastDialed != host to port) lastSessionId = null
        lastDialed = host to port
        acquireMulticastLock()

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
        teardownRuntime() // also stops the signaling loop and sends a best-effort Bye
        _connection.value = ConnectionState.Disconnected
        _peerState.value = PeerState.CLOSED
        _remoteVideoTrack.value = null
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        audioReceiver?.setMuted(muted)
    }

    fun play() {
        syncEngine?.sendPlayRequest(_playhead.value.positionMs)
    }

    fun pause() {
        syncEngine?.sendPauseRequest(_playhead.value.positionMs)
    }

    fun seekTo(positionMs: Long) {
        syncEngine?.sendSeekRequest(positionMs)
    }

    fun setSpeed(speed: Float) {
        syncEngine?.sendSpeedRequest(speed)
    }

    // ---- Wiring ----

    private fun signalingCallbacks(peers: ClientPeerManager) = object : SignalingClient.Callbacks {
        override fun onWelcome(sessionId: String, masterName: String) {
            lastSessionId = sessionId
            val room = _currentRoom.value
            if (room != null && room.label == room.host && masterName.isNotBlank()) {
                _currentRoom.value = room.copy(label = masterName)
            }
        }

        override fun onOffer(sdp: String) {
            peers.onRemoteOffer(sdp)
        }

        override fun onRemoteIce(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
            Log.i(TAG, "remote ICE candidate: $candidate")
            peers.onRemoteIce(sdpMid, sdpMLineIndex, candidate)
        }

        override fun onClosed(reason: String?) {
            // A close without a Bye is a network blip (e.g. ping timeout): SignalingClient keeps
            // retrying, so stay in Connecting and let the UI show "Reconnecting…".
            if (reason == SOCKET_CLOSED) return
            // A Bye: the master refused us or ended the room. Surface CLOSED (terminal UI) and,
            // for an ended room, stop the pointless reconnect loop. A rejection already ended it.
            roomEnded = true
            _peerState.value = PeerState.CLOSED
            val client = signaling
            if (client != null && client.connection.value !is ConnectionState.Rejected) {
                client.disconnect("host ended")
            }
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
            Log.i(TAG, "local ICE candidate: ${c.sdp}")
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
            Log.i(TAG, "peer state -> $state")
            if (!roomEnded) _peerState.value = state
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
        // Stop the old client's connect/reconnect loop; otherwise an unreachable (or previous)
        // room keeps retrying in viewModelScope after we've moved on. Idempotent.
        signaling?.disconnect("client left")
        signaling = null
        core = null
        _remoteVideoTrack.value = null
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        multicastLock = wifiManager.createMulticastLock("SyncStreamClientSession").also {
            it.setReferenceCounted(false)
            it.acquire()
            Log.d(TAG, "Session MulticastLock acquired")
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.let {
            it.release()
            Log.d(TAG, "Session MulticastLock released")
        }
        multicastLock = null
    }

    override fun onCleared() {
        teardownRuntime()
        discoverer.stop()
        super.onCleared()
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private companion object {
        const val TAG = "ClientViewModel"
        const val STATS_POLL_MS = 2_000L
        const val ATTEMPT_TIMEOUT_MS = 5_000L

        /** SignalingClient's reason for a socket that closed without a Bye (it then retries). */
        const val SOCKET_CLOSED = "socket closed"
    }
}

/** Join-code screen state. */
sealed interface JoinStatus {
    data object Idle : JoinStatus
    data object Trying : JoinStatus
    data class Done(val result: MatchResult) : JoinStatus
}
