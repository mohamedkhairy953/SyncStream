package com.syncstream.ui.master

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.People
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Full-screen Netflix/Prime-style master video player. Shares the same [MasterViewModel] instance
 * as [MasterScreen] (scoped to the masterGraph back-stack entry) so the streaming session keeps
 * running while this screen is shown.
 *
 * Interaction model:
 * - Tapping the video toggles play/pause; when paused, a centred play glyph is shown.
 * - Controls live in a persistent bottom panel: row 1 is the seek bar, row 2 is options + info.
 * - Immersive display (system bars hidden, screen kept awake); hardware-back returns to setup
 *   without stopping the session.
 */
@Composable
fun MasterPlayerScreen(
    viewModel: MasterViewModel,
    onBack: () -> Unit,
    onStopHosting: () -> Unit,
) {
    val bound by viewModel.bound.collectAsStateWithLifecycle()
    val pin by viewModel.pin.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val selectedUri by viewModel.selectedUri.collectAsStateWithLifecycle()
    val thermalWarning by viewModel.thermalWarning.collectAsStateWithLifecycle()
    val loop by viewModel.loop.collectAsStateWithLifecycle()

    // Self-heal: if the service somehow isn't bound, bind it (idempotent). Reading the bound state
    // below makes eglContext/videoSource (plain getters) refresh once the service connects.
    LaunchedEffect(Unit) { viewModel.bind() }
    @Suppress("UNUSED_EXPRESSION") bound
    val eglContext = viewModel.eglContext
    val videoSource = viewModel.videoSource

    // ---- File picker for "Select video" ----
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onMediaPicked) }

    // ---- Immersive + keep-awake ----
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, view)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        view.keepScreenOn = true
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
        }
    }

    // ---- Hardware back -> setup, keep hosting ----
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Tap the video to toggle play/pause.
            .pointerInput(Unit) {
                detectTapGestures {
                    if (playback.playing) viewModel.pause() else viewModel.play()
                }
            },
    ) {
        // ---- Video layer ----
        if (eglContext == null || videoSource == null) {
            Text(
                "Preparing stream…",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            val holder = remember { object { var view: SurfaceViewRenderer? = null } }
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
                        holder.view = this
                        videoSource.addPreviewSink(this)
                    }
                },
            )
            DisposableEffect(videoSource) {
                onDispose {
                    holder.view?.let { v ->
                        videoSource.removePreviewSink(v)
                        v.release()
                    }
                    holder.view = null
                }
            }
        }

        // ---- Top scrim + bar ----
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))),
        )
        TopBar(
            title = selectedUri?.lastPathSegment ?: "No video selected",
            onBack = onBack,
            onStopHosting = onStopHosting,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // ---- Centre play glyph (only while paused) ----
        if (!playback.playing) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(76.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { viewModel.play() },
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

        // ---- Bottom scrim + control panel ----
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(170.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
        )
        BottomPanel(
            positionMs = playback.positionMs,
            durationMs = playback.durationMs,
            playing = playback.playing,
            loop = loop,
            pin = pin,
            clientCount = clients.size,
            thermalWarning = thermalWarning,
            onSeek = viewModel::seekTo,
            onRewind = { viewModel.seekTo((playback.positionMs - 10_000L).coerceAtLeast(0L)) },
            onForward = { viewModel.seekTo(playback.positionMs + 10_000L) },
            onToggleLoop = { viewModel.setLoop(!loop) },
            onSelectVideo = { openDocumentLauncher.launch(arrayOf("video/*")) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    onStopHosting: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        TextButton(onClick = onStopHosting) {
            Text("Stop hosting", color = Color(0xFFFF6B6B), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BottomPanel(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    loop: Boolean,
    pin: String,
    clientCount: Int,
    thermalWarning: Boolean,
    onSeek: (Long) -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onToggleLoop: () -> Unit,
    onSelectVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val duration = durationMs.coerceAtLeast(0L)
    val sliderMax = duration.coerceAtLeast(1L).toFloat()

    // Smooth, synced position: ExoPlayer only publishes on discrete events, so advance locally
    // while playing and resync whenever the authoritative positionMs/playing/duration change.
    var ticked by remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, playing, duration) {
        ticked = positionMs
        if (playing && duration > 0) {
            while (true) {
                delay(500)
                ticked = (ticked + 500).coerceAtMost(duration)
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
            .padding(bottom = 18.dp),
    ) {
        // ---- Row 1: progress bar ----
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
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text(
                formatMs(duration),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }

        // ---- Row 2: options (left) + information & actions (right) ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRewind) {
                    Icon(Icons.Filled.Replay10, "Rewind 10s", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = onForward) {
                    Icon(Icons.Filled.Forward10, "Forward 10s", tint = Color.White, modifier = Modifier.size(26.dp))
                }
                IconButton(onClick = onToggleLoop) {
                    Icon(
                        Icons.Filled.Repeat,
                        contentDescription = "Loop",
                        tint = if (loop) MaterialTheme.colorScheme.primary else Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pin.isNotEmpty()) {
                    Text(
                        "PIN $pin",
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Icon(
                    Icons.Outlined.People,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    "$clientCount/4",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (thermalWarning) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = "Thermal throttling",
                        tint = Color(0xFFF9A825),
                        modifier = Modifier.size(15.dp),
                    )
                }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onSelectVideo) {
                    Icon(
                        Icons.Filled.VideoLibrary,
                        contentDescription = "Select another video",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
