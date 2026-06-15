package com.syncstream

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Manual DI root holding ONLY app-scoped primitives that must be singletons for the entire
 * process lifetime (no Hilt). Higher-level objects (Ktor server, WebRTC managers, player)
 * are composed by [com.syncstream.service.MasterStreamingService], never here, to keep the
 * dependency direction acyclic.
 *
 * Lifetime: created once in [SyncStreamApp.onCreate]; the EglBase + [PeerConnectionFactory]
 * stay alive across master sessions and are NEVER released on teardown.
 */
class AppContainer(private val appContext: Context) {

    /** Application-scoped coroutine scope (SupervisorJob; default dispatcher). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob())

    /** Shared JSON for any general app use (signaling/sync each have their own configured Json). */
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * The ONE app-scoped EglBase. Shared by SurfaceTextureHelper, the video encoder/decoder
     * factories, and every SurfaceViewRenderer. Must be the single GL context in the process.
     */
    val eglBase: EglBase by lazy { EglBase.create() }

    /** Convenience accessor for the shared EGL context. */
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    /**
     * Audio device module that NEVER records from the mic (no RECORD_AUDIO permission). Input
     * is effectively disabled; output is used only if WebRTC routes any track to it. SyncStream
     * streams PCM over a DataChannel instead, so this exists mainly to satisfy the factory.
     */
    private val audioDeviceModule: JavaAudioDeviceModule by lazy {
        JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .createAudioDeviceModule()
    }

    /**
     * The ONE [PeerConnectionFactory]. Built with hardware-preferred video codecs using the
     * shared EGL context. H264 high-profile + Intel VP8 enabled in the encoder factory.
     * Requires [PeerConnectionFactory.initialize] to have run (done in [SyncStreamApp]).
     */
    val peerConnectionFactory: PeerConnectionFactory by lazy {
        val encoderFactory = DefaultVideoEncoderFactory(
            eglContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true,
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglContext)
        PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    /** Tears down only the app scope. EGL/factory intentionally persist for the process. */
    fun shutdown() {
        appScope.cancel()
    }
}
