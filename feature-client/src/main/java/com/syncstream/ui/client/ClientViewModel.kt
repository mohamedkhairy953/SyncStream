package com.syncstream.ui.client

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syncstream.appContainer
import com.syncstream.discovery.DiscoveredService
import com.syncstream.discovery.DiscoveryState
import com.syncstream.discovery.NsdDiscoverer
import com.syncstream.signaling.ConnectionState
import com.syncstream.core.PeerState
import com.syncstream.core.renderPdfPage
import com.syncstream.signaling.SignalingClient
import com.syncstream.sync.ClockSync
import com.syncstream.sync.PlayheadInfo
import com.syncstream.sync.SyncEngine
import com.syncstream.sync.AudioReceiver
import com.syncstream.webrtc.ClientPeerManager
import com.syncstream.webrtc.WebRtcCore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    // ---- Discrete presentations (image / pdf / file), fetched from the master's GET /media ----

    /** Decoded photo, or the rendered current PDF page, when the host presents one. */
    private val _sharedBitmap = MutableStateFlow<Bitmap?>(null)
    val sharedBitmap: StateFlow<Bitmap?> = _sharedBitmap.asStateFlow()

    /** True while the current item's payload is being downloaded from the host. */
    private val _mediaDownloading = MutableStateFlow(false)
    val mediaDownloading: StateFlow<Boolean> = _mediaDownloading.asStateFlow()

    /** Download progress 0..1 while [mediaDownloading] (-1 when the host didn't send a size). */
    private val _mediaProgress = MutableStateFlow(0f)
    val mediaProgress: StateFlow<Float> = _mediaProgress.asStateFlow()

    /** The fully received file when the host shares a generic file; null until it has arrived. */
    private val _sharedFile = MutableStateFlow<File?>(null)
    val sharedFile: StateFlow<File?> = _sharedFile.asStateFlow()

    /** Outcome of [saveSharedFile] / [saveSharedFileTo] for the current item. */
    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    private var loadedMediaId: String? = null
    private var mediaDownloadJob: Job? = null

    /** Guest-side renderer over the downloaded PDF; one page rendered at a time under [pdfMutex]. */
    private var pdfRenderer: PdfRenderer? = null
    private var pdfFd: ParcelFileDescriptor? = null
    private val pdfMutex = Mutex()
    private var renderedPage = -1
    private var renderJob: Job? = null

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
        collectors += viewModelScope.launch {
            sync.clientPlayhead.collect { ph ->
                _playhead.value = ph
                onPlayheadMediaUpdated(ph)
            }
        }

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

    /** PDF: ask the host to show 0-based [page] to everyone (like play/pause, it's for the room). */
    fun showPage(page: Int) {
        syncEngine?.sendPageRequest(page)
    }

    fun nextPage() = showPage(_playhead.value.mediaPage + 1)
    fun previousPage() = showPage(_playhead.value.mediaPage - 1)

    /**
     * Saves the received file into the device's Downloads via MediaStore (API 29+; no storage
     * permission needed). Older devices use [saveSharedFileTo] with a SAF-created document.
     */
    fun saveSharedFile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val src = _sharedFile.value ?: return
        val ph = _playhead.value
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            val name = ph.mediaTitle ?: src.name
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeFor(name))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("insert returned null")
                    resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                        ?: error("no output stream")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    name
                }
            }
            _saveState.value = result.fold(
                onSuccess = { SaveState.Saved(it) },
                onFailure = { Log.w(TAG, "saveSharedFile failed", it); SaveState.Failed },
            )
        }
    }

    /** Copies the received file into [target] (a document the guest created via SAF). */
    fun saveSharedFileTo(target: Uri) {
        val src = _sharedFile.value ?: return
        val name = _playhead.value.mediaTitle ?: src.name
        _saveState.value = SaveState.Saving
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                        src.inputStream().use { it.copyTo(out) }
                    } ?: error("no output stream")
                }.isSuccess
            }
            _saveState.value = if (ok) SaveState.Saved(name) else SaveState.Failed
        }
    }

    /** MIME type guessed from the shared file's name (for the Downloads entry). */
    fun sharedFileMimeType(): String = mimeTypeFor(_playhead.value.mediaTitle ?: _sharedFile.value?.name ?: "")

    private fun mimeTypeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
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

    /**
     * Folds the heartbeat's presentation fields into the discrete-media state: a new image/pdf/
     * file id starts a download from the host (cached per id under cacheDir), a PDF page change
     * re-renders from the local copy, and a switch back to timed media clears everything.
     */
    private fun onPlayheadMediaUpdated(ph: PlayheadInfo) {
        val discrete = ph.mediaKind == "image" || ph.mediaKind == "pdf" || ph.mediaKind == "file"
        if (!discrete) {
            if (loadedMediaId != null || _sharedBitmap.value != null || mediaDownloadJob != null) resetDiscreteMedia()
            return
        }
        val id = ph.mediaId ?: return
        if (id == loadedMediaId) {
            // Same item: only a PDF page flip needs work, and only once it has been downloaded.
            if (ph.mediaKind == "pdf" && pdfRenderer != null && ph.mediaPage != renderedPage) {
                renderJob?.cancel()
                renderJob = viewModelScope.launch { renderSharedPdfPage(ph.mediaPage) }
            }
            return
        }
        resetDiscreteMedia()
        loadedMediaId = id
        mediaDownloadJob = viewModelScope.launch {
            _mediaDownloading.value = true
            _mediaProgress.value = 0f
            val dest = File(getApplication<Application>().cacheDir, "shared_$id")
            val cached = dest.exists() && dest.length() > 0L
            val ok = cached || signaling?.downloadMedia(id, dest) { read, total ->
                _mediaProgress.value = if (total > 0L) (read.toFloat() / total).coerceIn(0f, 1f) else -1f
            } == true
            if (!ok || !dest.exists()) {
                loadedMediaId = null
                _mediaDownloading.value = false
                return@launch
            }
            _mediaProgress.value = 1f
            when (ph.mediaKind) {
                "image" -> _sharedBitmap.value = decodeSampledFile(dest)
                "pdf" -> if (openSharedPdf(dest)) renderSharedPdfPage(_playhead.value.mediaPage)
                "file" -> _sharedFile.value = dest
            }
            _mediaDownloading.value = false
        }
    }

    private fun resetDiscreteMedia() {
        mediaDownloadJob?.cancel()
        mediaDownloadJob = null
        renderJob?.cancel()
        renderJob = null
        closeSharedPdf()
        loadedMediaId = null
        _sharedBitmap.value = null
        _sharedFile.value = null
        _saveState.value = SaveState.Idle
        _mediaDownloading.value = false
        _mediaProgress.value = 0f
    }

    private suspend fun decodeSampledFile(file: File): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        }.getOrNull()
    }

    private suspend fun openSharedPdf(file: File): Boolean = withContext(Dispatchers.IO) {
        pdfMutex.withLock {
            runCatching {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfFd = fd
                pdfRenderer = PdfRenderer(fd)
                renderedPage = -1
                true
            }.getOrDefault(false)
        }
    }

    private suspend fun renderSharedPdfPage(page: Int) {
        val bitmap = withContext(Dispatchers.IO) {
            pdfMutex.withLock {
                val renderer = pdfRenderer ?: return@withLock null
                renderPdfPage(renderer, page)
            }
        }
        if (bitmap != null) {
            renderedPage = page
            _sharedBitmap.value = bitmap
        }
    }

    private fun closeSharedPdf() {
        runCatching { pdfRenderer?.close() }
        runCatching { pdfFd?.close() }
        pdfRenderer = null
        pdfFd = null
        renderedPage = -1
    }

    private fun teardownRuntime() {
        resetDiscreteMedia()
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

/** Outcome of saving a shared file to the device. */
sealed interface SaveState {
    data object Idle : SaveState
    data object Saving : SaveState
    data class Saved(val name: String) : SaveState
    data object Failed : SaveState
}
