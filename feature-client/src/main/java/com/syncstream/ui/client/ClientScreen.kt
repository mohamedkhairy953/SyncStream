package com.syncstream.ui.client

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncstream.core.PeerState
import com.syncstream.signaling.ConnectionState
import com.syncstream.sync.PlayheadInfo
import com.syncstream.ui.components.PlayerOverlayScaffold
import com.syncstream.ui.components.PrimaryButton
import com.syncstream.ui.components.SecondaryButton
import com.syncstream.ui.components.SeekBar
import com.syncstream.ui.components.SpeedMenu
import com.syncstream.ui.components.StatusPill
import com.syncstream.ui.components.StatusTone
import com.syncstream.ui.components.TransportControls
import com.syncstream.ui.components.rememberOverlayVisibility
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncTheme
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

private const val SEEK_STEP_MS = 10_000L
private const val TICK_MS = 500L
private const val CONTROL_HINT_MS = 4_000L
private val CenterPlaySize = 76.dp
private val CenterPlayIconSize = 44.dp
private val SpinnerSize = 40.dp

/**
 * Guest · Watch. Full-bleed viewer: renders the master's video track into a [SurfaceViewRenderer]
 * (initialized exactly once with the shared `EglBase.eglBaseContext`; the [DisposableEffect]
 * removes the sink THEN releases the renderer), under [PlayerOverlayScaffold]'s auto-hiding bars:
 * leave, sync status, sound (DEFAULT MUTED) and speed on top; seek bar and transport below.
 * Centre states cover connecting, waiting for the host's video, reconnecting and terminal ends.
 *
 * The [viewModel] is already connecting (Join code / Scan started it); this screen never dials.
 * Tap semantics: with the bars hidden a tap shows them; with them shown a tap toggles play/pause.
 */
@Composable
fun ClientScreen(viewModel: ClientViewModel, onLeave: () -> Unit) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val peerState by viewModel.peerState.collectAsStateWithLifecycle()
    val playhead by viewModel.playhead.collectAsStateWithLifecycle()
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    val latencyMs by viewModel.latencyBadgeMs.collectAsStateWithLifecycle()
    val remoteTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()

    val eglContext = remember { viewModel.eglContext }
    val label = viewModel.currentRoom?.label ?: "the host"

    val leave: () -> Unit = {
        viewModel.disconnect()
        onLeave()
    }
    BackHandler(onBack = leave)

    // Keep the screen awake (no dim) only while the master is actively playing. Pause / end / leave
    // releases the flag so the device can dim normally.
    val view = LocalView.current
    DisposableEffect(playhead.playing) {
        view.keepScreenOn = playhead.playing
        onDispose { view.keepScreenOn = false }
    }

    // ---- Overlay + first-use hint ----
    val overlay = rememberOverlayVisibility(playhead.playing)
    var hintShown by rememberSaveable { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hintVisible) {
        if (hintVisible) {
            delay(CONTROL_HINT_MS)
            hintVisible = false
        }
    }
    val onControl: () -> Unit = {
        overlay.poke()
        if (!hintShown) {
            hintShown = true
            hintVisible = true
        }
    }

    val togglePlayPause = {
        if (playhead.playing) viewModel.pause() else viewModel.play()
    }

    // ---- Connection phases ----
    var wasConnected by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connection) {
        if (connection is ConnectionState.Connected) wasConnected = true
    }
    val terminal: Pair<String, String>? = when {
        connection is ConnectionState.Rejected -> {
            val reason = (connection as ConnectionState.Rejected).reason
            "Couldn't join $label" to when (RoomCodeMatcher.classifyRejection(reason)) {
                AttemptResult.WrongCode -> "The room code was wrong."
                else -> "That room is full (4 guests)."
            }
        }
        peerState == PeerState.CLOSED -> "The host ended the room" to "The streaming session has ended."
        else -> null
    }
    val connected = connection is ConnectionState.Connected

    PlayerOverlayScaffold(
        visible = overlay.visible,
        onTap = {
            if (overlay.visible) {
                onControl()
                togglePlayPause()
            } else {
                overlay.poke()
            }
        },
        modifier = Modifier.background(Color.Black),
        video = {
            VideoSurface(eglContext = eglContext, track = remoteTrack)
        },
        topBar = {
            IconButton(onClick = leave) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave room", tint = SyncTheme.colors.onScrim)
            }
            if (connected && latencyMs > 0) {
                StatusPill("Synced · $latencyMs ms", tone = StatusTone.Success)
            } else {
                StatusPill("Syncing…", tone = StatusTone.Warning)
            }
            Spacer(Modifier.weight(1f))
            SoundPill(muted = muted, onToggle = { overlay.poke(); viewModel.setMuted(!muted) })
            Spacer(Modifier.width(SyncSpacing.s))
            SpeedMenu(speed = playhead.speed, onSetSpeed = { onControl(); viewModel.setSpeed(it) })
        },
        bottomBar = {
            val position = rememberTickedPosition(playhead)
            SeekBar(
                positionMs = position,
                durationMs = playhead.durationMs.coerceAtLeast(0L),
                onSeek = { onControl(); viewModel.seekTo(it) },
                onScrub = overlay::poke,
            )
            TransportControls(
                playing = playhead.playing,
                onPlayPause = { onControl(); togglePlayPause() },
                onRewind = { onControl(); viewModel.seekTo((playhead.positionMs - SEEK_STEP_MS).coerceAtLeast(0L)) },
                onForward = { onControl(); viewModel.seekTo(playhead.positionMs + SEEK_STEP_MS) },
            )
            if (hintVisible) {
                Text(
                    "Pausing pauses for everyone",
                    style = MaterialTheme.typography.bodySmall,
                    color = SyncTheme.colors.onScrimMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = SyncSpacing.xs),
                )
            }
        },
        centerContent = {
            when {
                terminal != null -> CenterMessage(
                    title = terminal.first,
                    body = terminal.second,
                    dim = true,
                ) { PrimaryButton("Back to home", onClick = leave) }

                wasConnected && !connected -> CenterMessage(
                    title = "Reconnecting to $label…",
                    body = "Still on the same Wi-Fi?",
                    dim = true,
                    spinner = true,
                ) { SecondaryButton("Leave room", onClick = leave) }

                !connected -> CenterMessage(
                    title = "Connecting to $label…",
                    body = null,
                    dim = true,
                    spinner = true,
                )

                remoteTrack == null -> CenterMessage(
                    title = "You're in $label's room",
                    body = "Waiting for the host to pick a video",
                    dim = false,
                ) { SoundPill(muted = muted, onToggle = { viewModel.setMuted(!muted) }) }

                overlay.visible && !playhead.playing -> CenterPlay(onClick = { onControl(); togglePlayPause() })
            }
        },
    )
}

/** The remote video, scaled to fit. Sinks are swapped as the track changes; released on dispose. */
@Composable
private fun VideoSurface(eglContext: org.webrtc.EglBase.Context, track: org.webrtc.VideoTrack?) {
    val renderer = remember { object { var view: SurfaceViewRenderer? = null } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                init(eglContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                renderer.view = this
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            renderer.view?.release()
            renderer.view = null
        }
    }

    DisposableEffect(track) {
        val view = renderer.view
        if (view != null && track != null) track.addSink(view)
        onDispose {
            if (view != null && track != null) track.removeSink(view)
        }
    }
}

/**
 * The playhead advanced locally between the master's (infrequent) playhead updates, so the seek
 * bar moves smoothly while playing.
 */
@Composable
private fun rememberTickedPosition(playhead: PlayheadInfo): Long {
    val duration = playhead.durationMs.coerceAtLeast(0L)
    var ticked by remember { mutableLongStateOf(playhead.positionMs) }
    LaunchedEffect(playhead.positionMs, playhead.playing, duration, playhead.speed) {
        ticked = playhead.positionMs
        if (playhead.playing && duration > 0) {
            while (true) {
                delay(TICK_MS)
                ticked = (ticked + (TICK_MS * playhead.speed).toLong()).coerceAtMost(duration)
            }
        }
    }
    return ticked
}

@Composable
private fun SoundPill(muted: Boolean, onToggle: () -> Unit) {
    StatusPill(
        if (muted) "Tap for sound" else "Sound on",
        icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        onClick = onToggle,
    )
}

@Composable
private fun CenterMessage(
    title: String,
    body: String?,
    dim: Boolean,
    spinner: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = SyncTheme.colors
    Box(
        Modifier
            .fillMaxSize()
            .then(if (dim) Modifier.background(colors.scrim) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(SyncSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SyncSpacing.s),
        ) {
            if (spinner) {
                CircularProgressIndicator(color = colors.onScrim, modifier = Modifier.size(SpinnerSize))
                Spacer(Modifier.size(SyncSpacing.s))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onScrim, textAlign = TextAlign.Center)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onScrimMuted, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.size(SyncSpacing.m))
                action()
            }
        }
    }
}

@Composable
private fun CenterPlay(onClick: () -> Unit) {
    val colors = SyncTheme.colors
    Box(
        Modifier
            .size(CenterPlaySize)
            .background(colors.scrimPill, CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = colors.onScrim, modifier = Modifier.size(CenterPlayIconSize))
    }
}
