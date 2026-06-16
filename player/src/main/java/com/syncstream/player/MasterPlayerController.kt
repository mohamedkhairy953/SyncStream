package com.syncstream.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import com.syncstream.webrtc.ExoSurfaceVideoSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps a Media3 [ExoPlayer]. The player renders video into [ExoSurfaceVideoSource.surface] (so the
 * shared WebRTC [org.webrtc.VideoTrack] fans the same frames to every client AND the local preview)
 * and plays audio locally through a [DefaultAudioSink] chain whose first stage is [pcmTap]. The tap
 * is a pure pass-through for the speakers while siphoning a 48 kHz / mono copy of the PCM toward the
 * "audio" DataChannel.
 *
 * A media3 [MediaSession] is attached to legitimize the service's FOREGROUND_SERVICE_TYPE_MEDIA_
 * PLAYBACK type. This controller is constructed and owned by
 * [com.syncstream.service.MasterStreamingService]; the Activity never touches it.
 *
 * Threading: every member here is called from the main thread (the service hops to it via the
 * player's application looper). [player] is built on the calling thread's looper, which the service
 * guarantees is the main looper.
 */
@OptIn(UnstableApi::class)
class MasterPlayerController(
    private val context: Context,
    private val videoSource: ExoSurfaceVideoSource,
    private val pcmTap: PcmTapProcessor,
) {

    /**
     * Renderers factory that injects [pcmTap] at the head of the audio-processor chain so decoded
     * PCM passes through it (tapped + forwarded unchanged) before reaching the platform sink.
     */
    private val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(pcmTap))
                .build()
        }
    }.apply {
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
    }

    val player: ExoPlayer = ExoPlayer.Builder(context, renderersFactory)
        .setHandleAudioBecomingNoisy(true)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()

    val mediaSession: MediaSession = MediaSession.Builder(context, player)
        .setId("SyncStreamMaster")
        .build()

    private val _playback = MutableStateFlow(
        PlaybackInfo(playing = false, positionMs = 0L, durationMs = 0L, ended = false),
    )
    val playback: StateFlow<PlaybackInfo> = _playback.asStateFlow()

    /** Loop is handled manually (seek-to-0 on STATE_ENDED) so the master can re-broadcast Seek. */
    @Volatile
    private var loopEnabled = false

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // MANDATORY: size the capture texture before frames flow. Ignore unappliedRotationDegrees
            // (deprecated, always 0).
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoSource.setTextureSize(videoSize.width, videoSize.height)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                if (loopEnabled) {
                    // Restart locally; the caller observes ended==true once and re-broadcasts Seek(0).
                    player.seekTo(0L)
                    player.play()
                }
            }
            publishState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishState()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            publishState()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            // Duration becomes known after the timeline is prepared.
            publishState()
        }
    }

    init {
        player.setVideoSurface(videoSource.surface)
        player.addListener(listener)
        player.playWhenReady = false
    }

    /** Loads the OpenDocument [uri] (persistable permission already taken upstream) and prepares. */
    fun setMediaUri(uri: Uri) {
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        publishState()
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun currentPositionMs(): Long = player.currentPosition.coerceAtLeast(0L)

    fun durationMs(): Long {
        val d = player.duration
        return if (d == C.TIME_UNSET || d < 0L) 0L else d
    }

    fun isPlaying(): Boolean = player.isPlaying

    /** Enables/disables the loop behaviour (STATE_ENDED -> seek-to-0; caller broadcasts Seek). */
    fun setLoop(enabled: Boolean) {
        loopEnabled = enabled
    }

    /** Releases the MediaSession and player. The [videoSource] and [pcmTap] are owned elsewhere. */
    fun release() {
        player.removeListener(listener)
        mediaSession.release()
        player.release()
    }

    private fun publishState() {
        val ended = player.playbackState == Player.STATE_ENDED
        _playback.value = PlaybackInfo(
            playing = player.isPlaying,
            positionMs = currentPositionMs(),
            durationMs = durationMs(),
            ended = ended,
        )
    }
}

/**
 * Authoritative master playback snapshot surfaced to the UI and used to seed [SyncMessage.State]
 * heartbeats. [positionMs]/[durationMs] are in the media timeline; [PlaybackInfo.ended] reflects
 * ExoPlayer STATE_ENDED.
 */
data class PlaybackInfo(
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val ended: Boolean,
)
