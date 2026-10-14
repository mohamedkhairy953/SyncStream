package com.syncstream.ui.client

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syncstream.signaling.ConnectionState
import com.syncstream.core.PeerState
import com.syncstream.sync.PlayheadInfo
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Full-bleed client viewer. Renders the master's video track into a [SurfaceViewRenderer]
 * (initialized exactly once with the shared `EglBase.eglBaseContext`; the [DisposableEffect]
 * removes the sink THEN releases the renderer), overlaid with auto-hiding transport controls
 * (play/pause, seek, +/-10s), a latency badge, a mute toggle (DEFAULT MUTED), and
 * connection / ended overlays.
 */
@Composable
fun ClientScreen(
    host: String,
    port: Int,
    onExit: () -> Unit,
    viewModel: ClientViewModel = viewModel(),
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val peerState by viewModel.peerState.collectAsStateWithLifecycle()
    val playhead by viewModel.playhead.collectAsStateWithLifecycle()
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    val latencyMs by viewModel.latencyBadgeMs.collectAsStateWithLifecycle()
    val remoteTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()

    val eglContext = remember { viewModel.eglContext }

    // Dial on first composition using the PIN handed over from QrScanScreen.
    LaunchedEffect(host, port) {
        viewModel.connect(host, port, PendingConnection.pin)
    }

    // Keep the screen awake (no dim) only while the master is actively playing. Pause / end / leave
    // releases the flag so the device can dim normally.
    val view = LocalView.current
    DisposableEffect(playhead.playing) {
        view.keepScreenOn = playhead.playing
        onDispose { view.keepScreenOn = false }
    }

    // ---- Auto-hiding controls ----
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    val keepControlsAlive: () -> Unit = { interactionNonce++ }
    LaunchedEffect(controlsVisible, playhead.playing, interactionNonce) {
        if (controlsVisible && playhead.playing) {
            delay(3500)
            controlsVisible = false
        }
    }

    val togglePlayPause = {
        if (playhead.playing) viewModel.pause() else viewModel.play()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures {
                    if (controlsVisible) togglePlayPause() else controlsVisible = true
                    interactionNonce++
                }
            },
    ) {
        // ---- Video surface ----
        val renderer = remember {
            object {
                var view: SurfaceViewRenderer? = null
            }
        }

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

        DisposableEffect(remoteTrack) {
            val view = renderer.view
            val track = remoteTrack
            if (view != null && track != null) {
                track.addSink(view)
            }
            onDispose {
                if (view != null && track != null) {
                    track.removeSink(view)
                }
            }
        }

        // ---- Top overlay (latency, mute, leave) ----
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopOverlay(
                latencyMs = latencyMs,
                muted = muted,
                speed = playhead.speed,
                onToggleMute = { keepControlsAlive(); viewModel.setMuted(!muted) },
                onSetSpeed = { keepControlsAlive(); viewModel.setSpeed(it) },
                onDisconnect = {
                    viewModel.disconnect()
                    onExit()
                },
            )
        }

        // ---- Centre play glyph (only while paused and controls shown) ----
        AnimatedVisibility(
            visible = controlsVisible && !playhead.playing,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { keepControlsAlive(); togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(44.dp),
                )
            }
        }

        // ---- Bottom overlay (transport controls) ----
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(140.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
                )
                BottomControls(
                    playhead = playhead,
                    onSeek = { keepControlsAlive(); viewModel.seekTo(it) },
                    onRewind = { keepControlsAlive(); viewModel.seekTo((playhead.positionMs - 10_000L).coerceAtLeast(0L)) },
                    onForward = { keepControlsAlive(); viewModel.seekTo(playhead.positionMs + 10_000L) },
                    onTogglePlayPause = { keepControlsAlive(); togglePlayPause() },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // ---- Connection overlay (terminal / connecting) ----
        ConnectionOverlay(
            connection = connection,
            peerState = peerState,
            hasVideo = remoteTrack != null,
            onExit = {
                viewModel.disconnect()
                onExit()
            },
        )
    }
}

@Composable
private fun TopOverlay(
    latencyMs: Long,
    muted: Boolean,
    speed: Float,
    onToggleMute: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LatencyBadge(latencyMs)
        Spacer(Modifier.weight(1f))
        ClientSpeedButton(speed = speed, onSetSpeed = onSetSpeed)
        IconButton(
            onClick = onToggleMute,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
        ) {
            Icon(
                imageVector = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute" else "Mute",
                tint = Color.White,
            )
        }
        Button(onClick = onDisconnect) { Text("Leave") }
    }
}

@Composable
private fun LatencyBadge(latencyMs: Long) {
    val color = when {
        latencyMs < 150 -> Color(0xFF2E7D32) // green
        latencyMs < 250 -> Color(0xFFF9A825) // amber
        else -> Color(0xFFC62828)            // red
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            "${latencyMs}ms",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottomControls(
    playhead: PlayheadInfo,
    onSeek: (Long) -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = playhead.durationMs.coerceAtLeast(0L)
    val sliderMax = duration.coerceAtLeast(1L).toFloat()

    var ticked by remember { mutableLongStateOf(playhead.positionMs) }
    LaunchedEffect(playhead.positionMs, playhead.playing, duration, playhead.speed) {
        ticked = playhead.positionMs
        if (playhead.playing && duration > 0) {
            while (true) {
                delay(500)
                ticked = (ticked + (500L * playhead.speed).toLong()).coerceAtMost(duration)
            }
        }
    }

    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    val sliderValue = if (scrubbing) scrubValue else ticked.toFloat().coerceIn(0f, sliderMax)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatMs(sliderValue.toLong()),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
            Slider(
                value = sliderValue,
                onValueChange = {
                    scrubbing = true
                    scrubValue = it
                },
                onValueChangeFinished = {
                    scrubbing = false
                    onSeek(scrubValue.toLong())
                },
                valueRange = 0f..sliderMax,
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                thumb = {},
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(
                formatMs(duration),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            IconButton(onClick = onRewind) {
                Icon(Icons.Filled.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(28.dp))
            }
            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.White.copy(alpha = 0.15f), CircleShape),
            ) {
                Icon(
                    imageVector = if (playhead.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playhead.playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = onForward) {
                Icon(Icons.Filled.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun ConnectionOverlay(
    connection: ConnectionState,
    peerState: PeerState,
    hasVideo: Boolean,
    onExit: () -> Unit,
) {
    // Terminal / informational overlay precedence: rejection > master-ended > connecting.
    val terminal: Pair<String, String>? = when {
        connection is ConnectionState.Rejected ->
            "Rejected" to (connection.reason ?: "The master refused the connection (wrong PIN or full).")
        peerState == PeerState.CLOSED ->
            "Master ended" to "The streaming session has ended."
        else -> null
    }

    if (terminal != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text(terminal.first, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    terminal.second,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onExit) { Text("Back") }
            }
        }
        return
    }

    // Not terminal: show a connecting spinner until the first video frame source is attached.
    val connecting = connection is ConnectionState.Disconnected ||
        connection is ConnectionState.Connecting ||
        (connection is ConnectionState.Connected && !hasVideo)
    if (connecting) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(16.dp))
                Text(
                    if (connection is ConnectionState.Connected) "Waiting for video…" else "Connecting to master…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val SPEED_OPTIONS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientSpeedButton(speed: Float, onSetSpeed: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                formatSpeed(speed),
                color = if (speed != 1f) MaterialTheme.colorScheme.primary else Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            formatSpeed(option),
                            fontWeight = if (option == speed) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == speed) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    onClick = { onSetSpeed(option); expanded = false },
                )
            }
        }
    }
}

private fun formatSpeed(speed: Float): String {
    return if (speed == speed.toInt().toFloat()) {
        "${speed.toInt()}x"
    } else {
        "${speed}x"
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
