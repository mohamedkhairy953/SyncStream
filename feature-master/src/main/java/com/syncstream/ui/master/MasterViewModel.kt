package com.syncstream.ui.master

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syncstream.discovery.AdvertiseState
import com.syncstream.player.PlaybackInfo
import com.syncstream.webrtc.ExoSurfaceVideoSource
import com.syncstream.service.MasterStreamingService
import com.syncstream.signaling.ClientHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.webrtc.EglBase

/**
 * Binds [MasterStreamingService] and re-exposes its [StateFlow]s to [HostPickScreen]. Holds the
 * [ServiceConnection] but no core WebRTC/player objects. Handles the file-picker result with a
 * persistable permission and tracks POST_NOTIFICATIONS denial via the service's flow.
 */
class MasterViewModel(app: Application) : AndroidViewModel(app) {

    private var service: MasterStreamingService? = null
    private val collectors = mutableListOf<Job>()

    private val _bound = MutableStateFlow(false)
    val bound: StateFlow<Boolean> = _bound.asStateFlow()

    private val _pin = MutableStateFlow("")
    val pin: StateFlow<String> = _pin.asStateFlow()

    private val _sessionLabel = MutableStateFlow("")
    val sessionLabel: StateFlow<String> = _sessionLabel.asStateFlow()

    private val _clients = MutableStateFlow<List<ClientHandle>>(emptyList())
    val clients: StateFlow<List<ClientHandle>> = _clients.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackInfo(false, 0, 0, false))
    val playback: StateFlow<PlaybackInfo> = _playback.asStateFlow()

    private val _advertise = MutableStateFlow<AdvertiseState>(AdvertiseState.Idle)
    val advertise: StateFlow<AdvertiseState> = _advertise.asStateFlow()

    private val _endpoint = MutableStateFlow<String?>(null)
    val endpoint: StateFlow<String?> = _endpoint.asStateFlow()

    private val _thermalWarning = MutableStateFlow(false)
    val thermalWarning: StateFlow<Boolean> = _thermalWarning.asStateFlow()

    private val _notificationsDenied = MutableStateFlow(false)
    val notificationsDenied: StateFlow<Boolean> = _notificationsDenied.asStateFlow()

    private val _loop = MutableStateFlow(false)
    val loop: StateFlow<Boolean> = _loop.asStateFlow()

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()

    /** Presentation kind of the selection ("video"/"audio"/"image"/"pdf"/"file"), sent to guests via the heartbeat. */
    private var selectedKind: String = "video"

    /** Display name of the selection, shown on the master's audio/image/pdf/file presentation. */
    private val _selectedName = MutableStateFlow<String?>(null)
    val selectedName: StateFlow<String?> = _selectedName.asStateFlow()

    /** Size in bytes of the selection (-1 when unknown); shown on the file presentation. */
    private val _selectedSize = MutableStateFlow(-1L)
    val selectedSize: StateFlow<Long> = _selectedSize.asStateFlow()

    /** Decoded bitmap of the current image, or the rendered current PDF page (`kind` image/pdf). */
    private val _selectedBitmap = MutableStateFlow<Bitmap?>(null)
    val selectedBitmap: StateFlow<Bitmap?> = _selectedBitmap.asStateFlow()

    /** Current 0-based PDF page and page count, mirrored from the service (0/0 when not a PDF). */
    private val _mediaPage = MutableStateFlow(0)
    val mediaPage: StateFlow<Int> = _mediaPage.asStateFlow()
    private val _mediaPageCount = MutableStateFlow(0)
    val mediaPageCount: StateFlow<Int> = _mediaPageCount.asStateFlow()

    /** Open renderer for the selected PDF; pages are rendered one at a time under [pdfMutex]. */
    private var pdfFd: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val pdfMutex = Mutex()
    private var renderJob: Job? = null

    /** Last 10 videos that were actually streamed (persisted); newest first. */
    private val recentStore = RecentVideosStore(app)
    val recentVideos: StateFlow<List<RecentVideo>> = recentStore.recent

    /** Guards [maybeRecordRecent] so resuming after a pause doesn't re-record the same video. */
    private var lastRecordedUri: Uri? = null

    /** Shared EGL context for the preview renderer; null until bound + streaming started. */
    val eglContext: EglBase.Context? get() = service?.eglContextProvider()?.eglBaseContext

    /** The single video source whose track feeds the local preview sink. */
    val videoSource: ExoSurfaceVideoSource? get() = service?.videoSourceProvider()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? MasterStreamingService.LocalBinder)?.service() ?: return
            service = svc
            _bound.value = true
            observeService(svc)
            // Go live as soon as the screen binds, so clients can discover and join this master
            // BEFORE a video is picked. startStreaming() is idempotent and does not require media;
            // the selected URI (if any) is loaded into the already-live session below.
            svc.startStreaming()
            _selectedUri.value?.let { svc.setMediaUri(it, selectedKind, displayName(it)) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _bound.value = false
            cancelCollectors()
        }
    }

    fun bind() {
        if (_bound.value) return
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, MasterStreamingService::class.java)
        // Ensure the service exists for the foreground-start lifecycle.
        ctx.startService(intent)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!_bound.value) return
        cancelCollectors()
        runCatching { getApplication<Application>().unbindService(connection) }
        service = null
        _bound.value = false
    }

    /**
     * Selects [uri] (presentation [kind]) and loads it into the (already live) room PAUSED at 0;
     * the host starts playback from the player ("Play for everyone" or the play button). Loads
     * exactly once: if the service isn't bound yet, [onServiceConnected] loads the selection
     * instead.
     */
    fun onMediaPicked(uri: Uri, kind: String = "video") {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = displayName(uri)
        _selectedUri.value = uri
        selectedKind = kind
        _selectedName.value = name
        _selectedSize.value = querySize(uri)
        renderJob?.cancel()
        closePdf()
        _selectedBitmap.value = null
        _mediaPage.value = 0
        _mediaPageCount.value = 0
        when (kind) {
            "image" -> renderJob = viewModelScope.launch {
                _selectedBitmap.value = decodeSampledBitmap(uri)
            }
            "pdf" -> renderJob = viewModelScope.launch {
                if (openPdf(uri)) renderPdfPage(0)
            }
        }
        if (kind != "video" && kind != "audio") {
            lastRecordedUri = uri
            recentStore.record(uri, name, kind)
        }
        val svc = service ?: return
        svc.setMediaUri(uri, kind, name)
        // The load is synchronous and paused; reflect it now so the player screen's first
        // composition (invite auto-open) doesn't see the previous video's "playing".
        _playback.value = _playback.value.copy(playing = false, positionMs = 0L, durationMs = 0L, ended = false, kind = kind)
    }

    /** Re-select an item from the recent-history list (video or audio, per its recorded kind). */
    fun playRecent(recent: RecentVideo) {
        onMediaPicked(Uri.parse(recent.uri), recent.kind)
    }

    /** Makes the room live (idempotent; binding already does this). Does not reload the media. */
    fun startStreaming() {
        service?.startStreaming()
    }

    fun stopStreaming() {
        service?.stopStreaming()
    }

    fun play() { service?.play() }
    fun pause() { service?.pause() }
    fun seekTo(positionMs: Long) { service?.seekTo(positionMs) }
    fun setLoop(enabled: Boolean) {
        _loop.value = enabled
        service?.setLoop(enabled)
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed
        service?.setSpeed(speed)
    }

    /** PDF only: show 0-based [page] to everyone (the service clamps and broadcasts). */
    fun showPage(page: Int) {
        service?.showPage(page)
    }

    fun nextPage() = showPage(_mediaPage.value + 1)
    fun previousPage() = showPage(_mediaPage.value - 1)

    /** Whether POST_NOTIFICATIONS is currently granted (used to drive the runtime request). */
    fun hasNotificationPermission(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        return getApplication<Application>().checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun observeService(svc: MasterStreamingService) {
        cancelCollectors()
        collectors += viewModelScope.launch { svc.pin.collect { _pin.value = it } }
        collectors += viewModelScope.launch { svc.sessionLabel.collect { _sessionLabel.value = it } }
        collectors += viewModelScope.launch { svc.clients.collect { _clients.value = it } }
        collectors += viewModelScope.launch {
            svc.playback.collect {
                _playback.value = it
                maybeRecordRecent(it)
            }
        }
        collectors += viewModelScope.launch { svc.advertise.collect { _advertise.value = it } }
        collectors += viewModelScope.launch { svc.endpoint.collect { _endpoint.value = it } }
        collectors += viewModelScope.launch { svc.thermalWarning.collect { _thermalWarning.value = it } }
        collectors += viewModelScope.launch { svc.notificationsDenied.collect { _notificationsDenied.value = it } }
        collectors += viewModelScope.launch { svc.mediaPageCount.collect { _mediaPageCount.value = it } }
        collectors += viewModelScope.launch {
            svc.mediaPage.collect { page ->
                val changed = page != _mediaPage.value
                _mediaPage.value = page
                // A guest's page request lands here too: re-render the host's own view.
                if (changed && selectedKind == "pdf" && pdfRenderer != null) {
                    renderJob?.cancel()
                    renderJob = viewModelScope.launch { renderPdfPage(page) }
                }
            }
        }
    }

    /**
     * Records the current video into the recent-history list the first time playback starts for it
     * ("streaming actually starts"). Guarded by [lastRecordedUri] so resume-after-pause does not
     * re-record or re-order; a genuinely new selection promotes it to the front.
     */
    private fun maybeRecordRecent(playback: PlaybackInfo) {
        if (!playback.playing) return
        val uri = _selectedUri.value ?: return
        if (uri == lastRecordedUri) return
        lastRecordedUri = uri
        recentStore.record(uri, displayName(uri), selectedKind)
    }

    /** Resolves a human-readable title for [uri], falling back to its last path segment. */
    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return name ?: uri.lastPathSegment ?: uri.toString()
    }

    private suspend fun decodeSampledBitmap(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxDim / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    /** Size in bytes from the document provider, or -1 when it doesn't say. */
    private fun querySize(uri: Uri): Long {
        val resolver = getApplication<Application>().contentResolver
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        }.getOrDefault(-1L)
    }

    // ---- PDF rendering (host's own view; guests render from their downloaded copy) ----

    private suspend fun openPdf(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        pdfMutex.withLock {
            runCatching {
                val fd = getApplication<Application>().contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withLock false
                pdfFd = fd
                pdfRenderer = PdfRenderer(fd)
                true
            }.getOrDefault(false)
        }
    }

    private suspend fun renderPdfPage(page: Int) {
        val bitmap = withContext(Dispatchers.IO) {
            pdfMutex.withLock {
                val renderer = pdfRenderer ?: return@withLock null
                com.syncstream.core.renderPdfPage(renderer, page)
            }
        }
        if (bitmap != null) _selectedBitmap.value = bitmap
    }

    private fun closePdf() {
        runCatching { pdfRenderer?.close() }
        runCatching { pdfFd?.close() }
        pdfRenderer = null
        pdfFd = null
    }

    private fun cancelCollectors() {
        collectors.forEach { it.cancel() }
        collectors.clear()
    }

    override fun onCleared() {
        renderJob?.cancel()
        closePdf()
        unbind()
        super.onCleared()
    }
}
