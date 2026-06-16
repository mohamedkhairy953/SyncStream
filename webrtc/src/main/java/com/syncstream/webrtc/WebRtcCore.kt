package com.syncstream.webrtc

import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Thin factory wrapper around the app-scoped [PeerConnectionFactory] + [EglBase] (both supplied
 * by `AppContainer`). Holds NO per-peer state and is safe to share between the master and client
 * peer managers — every PeerConnection it mints uses [RtcConfigs.lanConfig].
 */
class WebRtcCore(
    val factory: PeerConnectionFactory,
    val eglBase: EglBase,
) {

    /**
     * Creates a PeerConnection wired to [observer] using the LAN-only RTCConfiguration. Returns
     * null if the native factory could not build the connection.
     */
    fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? =
        factory.createPeerConnection(RtcConfigs.lanConfig(), observer)

    /**
     * Creates a [SurfaceTextureHelper] backed by the shared EGL context. Caller owns disposal.
     */
    fun newSurfaceTextureHelper(threadName: String = "CaptureThread"): SurfaceTextureHelper =
        SurfaceTextureHelper.create(threadName, eglBase.eglBaseContext)

    /**
     * Creates a [VideoSource]. [isScreencast] = true for the ExoPlayer-driven capture path so the
     * adapter never drops frames on a quiet (static) image.
     */
    fun createVideoSource(isScreencast: Boolean = true): VideoSource =
        factory.createVideoSource(isScreencast)

    /** Creates a [VideoTrack] with the given [id] from [source]. */
    fun createVideoTrack(id: String, source: VideoSource): VideoTrack =
        factory.createVideoTrack(id, source)
}
