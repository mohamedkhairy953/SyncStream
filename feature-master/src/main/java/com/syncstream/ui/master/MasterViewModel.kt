package com.syncstream.ui.master

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.syncstream.discovery.AdvertiseState
import com.syncstream.player.PlaybackInfo
import com.syncstream.webrtc.ExoSurfaceVideoSource
import com.syncstream.service.MasterStreamingService
import com.syncstream.signaling.ClientHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase

/**
 * Binds [MasterStreamingService] and re-exposes its [StateFlow]s to [MasterScreen]. Holds the
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

    private val _thermalWarning = MutableStateFlow(false)
    val thermalWarning: StateFlow<Boolean> = _thermalWarning.asStateFlow()

    private val _notificationsDenied = MutableStateFlow(false)
    val notificationsDenied: StateFlow<Boolean> = _notificationsDenied.asStateFlow()

    private val _loop = MutableStateFlow(false)
    val loop: StateFlow<Boolean> = _loop.asStateFlow()

    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()

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
            _selectedUri.value?.let { svc.setMediaUri(it) }
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

    fun onMediaPicked(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        _selectedUri.value = uri
        service?.setMediaUri(uri)
    }

    fun startStreaming() {
        val svc = service ?: return
        svc.startStreaming()
        _selectedUri.value?.let { svc.setMediaUri(it) }
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
        collectors += viewModelScope.launch { svc.playback.collect { _playback.value = it } }
        collectors += viewModelScope.launch { svc.advertise.collect { _advertise.value = it } }
        collectors += viewModelScope.launch { svc.thermalWarning.collect { _thermalWarning.value = it } }
        collectors += viewModelScope.launch { svc.notificationsDenied.collect { _notificationsDenied.value = it } }
    }

    private fun cancelCollectors() {
        collectors.forEach { it.cancel() }
        collectors.clear()
    }

    override fun onCleared() {
        unbind()
        super.onCleared()
    }
}
