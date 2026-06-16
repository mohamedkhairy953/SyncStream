package com.syncstream.webrtc

import android.view.Surface
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Bridges ExoPlayer-rendered frames into ONE WebRTC [VideoSource]/[VideoTrack] (master only).
 *
 * The pipeline is: ExoPlayer renders into [surface] (a [Surface] wrapping the
 * [SurfaceTextureHelper]'s [android.graphics.SurfaceTexture]) -> the helper emits texture frames
 * on its capture thread -> each frame is fed to the video source's capturer observer -> the single
 * [videoTrack] fans the frames out to all peer connections and the local preview.
 *
 * MANDATORY: [setTextureSize] MUST be called (driven from `Player.Listener.onVideoSizeChanged`)
 * before frames will flow; without a texture size the helper produces no output.
 */
class ExoSurfaceVideoSource(private val core: WebRtcCore) {

    private val helper: SurfaceTextureHelper = core.newSurfaceTextureHelper("ExoCaptureThread")

    private val videoSource: VideoSource = core.createVideoSource(isScreencast = true)

    /** The single track fanned to every peer connection AND the master's local preview. */
    val videoTrack: VideoTrack = core.createVideoTrack(VIDEO_TRACK_ID, videoSource)

    /** Pass to `ExoPlayer.setVideoSurface(...)`. Backed by the helper's SurfaceTexture. */
    val surface: Surface = Surface(helper.surfaceTexture)

    @Volatile
    private var started = false

    @Volatile
    private var released = false

    init {
        // Default output cap until adaptOutputFormat is called with a different budget.
        videoSource.adaptOutputFormat(DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_FPS)
        start()
    }

    /**
     * Begins listening for texture frames and forwarding them into the video source. Called once
     * from [init]; idempotent. The helper must already have a texture size for frames to appear,
     * but startListening can be armed first.
     */
    @Synchronized
    fun start() {
        if (started || released) return
        helper.startListening { frame ->
            videoSource.capturerObserver.onFrameCaptured(frame)
        }
        started = true
    }

    /**
     * Stops forwarding frames into the video source without releasing the GL resources. Safe to
     * pause/resume capture across player state changes.
     */
    @Synchronized
    fun stop() {
        if (!started || released) return
        helper.stopListening()
        started = false
    }

    /**
     * MANDATORY before frames flow; driven from `Player.Listener.onVideoSizeChanged(w, h)`. Sizes
     * the helper's SurfaceTexture so produced frames have the correct dimensions. Ignore
     * `VideoSize.unappliedRotationDegrees` (deprecated, always 0).
     */
    fun setTextureSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        helper.setTextureSize(width, height)
    }

    /**
     * Caps the output resolution/frame rate of the shared source. Default is
     * [DEFAULT_WIDTH]x[DEFAULT_HEIGHT]@[DEFAULT_FPS]; SEVERE thermal pressure should drop this to
     * 960x540@24 via the caller.
     */
    fun adaptOutputFormat(width: Int, height: Int, fps: Int) {
        if (released) return
        videoSource.adaptOutputFormat(width, height, fps)
    }

    /** Adds a local-preview sink (the master's `SurfaceViewRenderer`) to the shared track. */
    fun addPreviewSink(sink: VideoSink) {
        if (released) return
        videoTrack.addSink(sink)
    }

    /** Removes a previously-added preview sink. */
    fun removePreviewSink(sink: VideoSink) {
        if (released) return
        videoTrack.removeSink(sink)
    }

    /**
     * Tears down capture: stop listening, release the Surface + SurfaceTextureHelper and dispose
     * the source/track. The app-scoped factory and EglBase are NEVER touched here.
     */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        if (started) {
            helper.stopListening()
            started = false
        }
        surface.release()
        helper.dispose()
        videoTrack.dispose()
        videoSource.dispose()
    }

    companion object {
        private const val VIDEO_TRACK_ID = "syncstream_video"
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
        const val DEFAULT_FPS = 30
    }
}
