package com.syncstream.ui.client

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.view.SurfaceHolder
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncstream.core.PeerState
import com.syncstream.core.formatByteSize
import com.syncstream.signaling.ConnectionState
import com.syncstream.sync.PlayheadInfo
import com.syncstream.ui.components.MediaKinds
import com.syncstream.ui.components.PageControls
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
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

private const val SEEK_STEP_MS = 10_000L
private const val TICK_MS = 500L
private const val CONTROL_HINT_MS = 4_000L
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private val CenterPlaySize = 76.dp
private val CenterPlayIconSize = 44.dp
private val SpinnerSize = 40.dp
private val CenterIconSize = 56.dp
private val DownloadBarWidth = 220.dp

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
    val sharedBitmap by viewModel.sharedBitmap.collectAsStateWithLifecycle()
    val mediaDownloading by viewModel.mediaDownloading.collectAsStateWithLifecycle()
    val mediaProgress by viewModel.mediaProgress.collectAsStateWithLifecycle()
    val sharedFile by viewModel.sharedFile.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    val isTimedMedia = MediaKinds.isTimed(playhead.mediaKind)
    val isPictured = playhead.mediaKind == MediaKinds.IMAGE || playhead.mediaKind == MediaKinds.PDF

    // Pre-Q devices have no permission-free MediaStore Downloads insert: save through a SAF
    // "create document" picker instead (the ViewModel copies into whatever the guest chose).
    val createDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri -> uri?.let { viewModel.saveSharedFileTo(it) } }
    val saveFile = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            viewModel.saveSharedFile()
        } else {
            createDocumentLauncher.launch(playhead.mediaTitle ?: "shared-file")
        }
    }

    val eglContext = remember { viewModel.eglContext }
    val currentRoom by viewModel.currentRoom.collectAsStateWithLifecycle()
    val label = currentRoom?.label ?: "the host"

    val leave: () -> Unit = {
        viewModel.disconnect()
        onLeave()
    }
    BackHandler(onBack = leave)

    // Keep the screen awake while the master is actively playing or presenting a static item.
    val view = LocalView.current
    val keepAwake = playhead.playing || !isTimedMedia
    DisposableEffect(keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
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

    // Live playback controls only make sense in a live room. In a terminal state (ended / couldn't
    // join) the bars keep just the back arrow, and they never auto-hide so it stays reachable.
    val live = terminal == null

    // ---- Overlay + first-use hint ----
    val overlay = rememberOverlayVisibility(playhead.playing && live)
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

    // ---- Display: the video is always letterboxed/pillarboxed (never cropped or distorted).
    // The screen follows the device sensor unless the rotate button was used to lock it.
    var videoAspect by remember { mutableFloatStateOf(0f) }
    var manualPortraitOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    val context = LocalContext.current
    val isPortraitNow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    DisposableEffect(manualPortraitOverride) {
        val activity = context.findActivity()
        val forced = manualPortraitOverride
        if (activity != null && forced != null) {
            activity.requestedOrientation = if (forced) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val resetZoom = {
        zoom = 1f
        pan = Offset.Zero
    }
    LaunchedEffect(playhead.mediaId, playhead.mediaPage) {
        resetZoom()
    }

    PlayerOverlayScaffold(
        visible = overlay.visible,
        onTap = {
            if (overlay.visible && live && isTimedMedia) {
                onControl()
                togglePlayPause()
            } else {
                overlay.poke()
            }
        },
        onDoubleTap = {
            overlay.poke()
            if (zoom > 1.05f) resetZoom()
        },
        onTransform = { zoomChange, panChange ->
            val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
            zoom = newZoom
            pan = if (newZoom <= 1.01f) Offset.Zero else pan + panChange
        },
        modifier = Modifier.background(Color.Black),
        video = {
            when {
                isPictured -> {
                    val bmp = sharedBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = playhead.mediaTitle,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = pan.x
                                    translationY = pan.y
                                },
                        )
                    }
                }
                playhead.mediaKind == MediaKinds.VIDEO -> {
                    VideoSurface(
                        eglContext = eglContext,
                        track = remoteTrack,
                        videoAspect = videoAspect,
                        onVideoAspectChanged = { videoAspect = it },
                        zoom = zoom,
                        pan = pan,
                    )
                }
            }
        },
        topBar = {
            IconButton(onClick = leave) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Leave room", tint = SyncTheme.colors.onScrim)
            }
            if (!live) return@PlayerOverlayScaffold
            // Sync status only while connected: when connecting / reconnecting the centre message
            // is the single source of truth, so a "Syncing…" pill would just repeat it.
            if (connected) {
                if (latencyMs > 0) {
                    StatusPill("Synced · $latencyMs ms", tone = StatusTone.Success)
                } else {
                    StatusPill("Syncing…", tone = StatusTone.Warning)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    overlay.poke()
                    val current = manualPortraitOverride ?: isPortraitNow
                    manualPortraitOverride = !current
                },
            ) {
                Icon(
                    Icons.Filled.ScreenRotation,
                    contentDescription = "Rotate screen",
                    tint = SyncTheme.colors.onScrim,
                )
            }
            if (isTimedMedia) {
                Spacer(Modifier.width(SyncSpacing.s))
                SoundPill(muted = muted, onToggle = { overlay.poke(); viewModel.setMuted(!muted) })
                Spacer(Modifier.width(SyncSpacing.s))
                SpeedMenu(speed = playhead.speed, onSetSpeed = { onControl(); viewModel.setSpeed(it) })
            }
        },
        bottomBar = {
            if (!live) return@PlayerOverlayScaffold
            if (isTimedMedia) {
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
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        playhead.mediaTitle ?: "Shared ${MediaKinds.noun(playhead.mediaKind)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = SyncTheme.colors.onScrim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (playhead.mediaKind == MediaKinds.PDF && playhead.mediaPageCount > 0) {
                        PageControls(
                            page = playhead.mediaPage,
                            pageCount = playhead.mediaPageCount,
                            onPrevious = { onControl(); viewModel.previousPage() },
                            onNext = { onControl(); viewModel.nextPage() },
                        )
                    }
                }
                if (hintVisible && playhead.mediaKind == MediaKinds.PDF) {
                    Text(
                        "Turning the page turns it for everyone",
                        style = MaterialTheme.typography.bodySmall,
                        color = SyncTheme.colors.onScrimMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = SyncSpacing.xs),
                    )
                }
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

                isPictured -> {
                    if (sharedBitmap == null) {
                        val noun = MediaKinds.noun(playhead.mediaKind)
                        CenterMessage(
                            title = playhead.mediaTitle ?: "Loading $noun…",
                            body = if (mediaDownloading) "Receiving the full-quality $noun from $label…" else "Waiting for the $noun…",
                            dim = false,
                            spinner = mediaDownloading,
                        ) { DownloadBar(mediaDownloading, mediaProgress) }
                    }
                }

                playhead.mediaKind == MediaKinds.FILE -> CenterMessage(
                    title = playhead.mediaTitle ?: "Shared file",
                    body = when {
                        sharedFile != null -> "${formatByteSize(playhead.mediaSize)} · received from $label"
                        mediaDownloading -> "${formatByteSize(playhead.mediaSize)} · receiving from $label…"
                        else -> "${formatByteSize(playhead.mediaSize)} · waiting for $label"
                    },
                    dim = false,
                    icon = MediaKinds.icon(MediaKinds.FILE),
                ) {
                    when {
                        sharedFile == null -> DownloadBar(mediaDownloading, mediaProgress)
                        saveState is SaveState.Saving -> CircularProgressIndicator(
                            color = SyncTheme.colors.onScrim,
                            modifier = Modifier.size(SpinnerSize),
                        )
                        saveState is SaveState.Saved -> StatusPill(
                            "Saved to Downloads",
                            tone = StatusTone.Success,
                            icon = Icons.Filled.Check,
                        )
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (saveState is SaveState.Failed) {
                                Text(
                                    "Couldn't save the file",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SyncTheme.colors.onScrimMuted,
                                )
                                Spacer(Modifier.size(SyncSpacing.s))
                            }
                            PrimaryButton("Save to device", onClick = saveFile, icon = Icons.Filled.Download)
                        }
                    }
                }

                playhead.mediaKind == MediaKinds.AUDIO -> CenterMessage(
                    title = playhead.mediaTitle ?: "Now playing",
                    body = "Audio — everyone hears the same moment",
                    dim = false,
                    icon = MediaKinds.icon(MediaKinds.AUDIO),
                ) { SoundPill(muted = muted, onToggle = { viewModel.setMuted(!muted) }) }

                remoteTrack == null -> CenterMessage(
                    title = "You're in $label's room",
                    body = "Waiting for the host to pick media",
                    dim = false,
                ) { SoundPill(muted = muted, onToggle = { viewModel.setMuted(!muted) }) }

                overlay.visible && !playhead.playing -> CenterPlay(onClick = { onControl(); togglePlayPause() })
            }
        },
    )
}

/** The remote video, letterboxed/pillarboxed to its own aspect (never cropped), with optional [zoom]/[pan]. Sinks are swapped as the track changes; released on dispose. */
@Composable
private fun VideoSurface(
    eglContext: org.webrtc.EglBase.Context,
    track: org.webrtc.VideoTrack?,
    videoAspect: Float,
    onVideoAspectChanged: (Float) -> Unit,
    zoom: Float,
    pan: Offset,
) {
    val renderer = remember {
        object {
            var view: SurfaceViewRenderer? = null
            var boundTrack: org.webrtc.VideoTrack? = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenAspect = if (maxHeight.value > 0f) maxWidth / maxHeight else 1f
        // Letterbox (wide video on a tall screen) or pillarbox (tall video on a wide screen):
        // the surface is sized to the video's aspect inside the screen bounds, so 100% of the
        // frame is always visible — nothing is cropped or stretched.
        val surfaceModifier = when {
            videoAspect <= 0f -> Modifier.fillMaxSize()
            videoAspect >= screenAspect -> Modifier.fillMaxWidth().aspectRatio(videoAspect)
            else -> Modifier.fillMaxHeight().aspectRatio(videoAspect)
        }
        val totalScale = zoom
        val maxPanX = (constraints.maxWidth * (totalScale - 1f) / 2f).coerceAtLeast(0f)
        val maxPanY = (constraints.maxHeight * (totalScale - 1f) / 2f).coerceAtLeast(0f)
        val clampedPanX = pan.x.coerceIn(-maxPanX, maxPanX)
        val clampedPanY = pan.y.coerceIn(-maxPanY, maxPanY)

        AndroidView(
            modifier = surfaceModifier
                .align(Alignment.Center)
                .graphicsLayer {
                    scaleX = totalScale
                    scaleY = totalScale
                    translationX = clampedPanX
                    translationY = clampedPanY
                },
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    init(
                        eglContext,
                        object : RendererCommon.RendererEvents {
                            override fun onFirstFrameRendered() {}
                            override fun onFrameResolutionChanged(w: Int, h: Int, rotation: Int) {
                                if (w > 0 && h > 0) {
                                    val aspect =
                                        if (rotation % 180 == 0) w.toFloat() / h
                                        else h.toFloat() / w
                                    onVideoAspectChanged(aspect)
                                }
                            }
                        },
                    )
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(false)
                    renderer.view = this
                    if (track != null) {
                        track.addSink(this)
                        renderer.boundTrack = track
                    }
                }
            },
            update = { view ->
                val prev = renderer.boundTrack
                if (prev !== track) {
                    prev?.removeSink(view)
                    track?.addSink(view)
                    renderer.boundTrack = track
                }
            },
        )

        DisposableEffect(Unit) {
            onDispose {
                val view = renderer.view
                val prev = renderer.boundTrack
                if (view != null && prev != null) {
                    runCatching { prev.removeSink(view) }
                }
                renderer.boundTrack = null
                view?.release()
                renderer.view = null
            }
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

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SoundPill(muted: Boolean, onToggle: () -> Unit) {
    StatusPill(
        if (muted) "Tap for sound" else "Sound on",
        icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        onClick = onToggle,
    )
}

/** Transfer progress under a centre message: determinate when the host sent a size. */
@Composable
private fun DownloadBar(downloading: Boolean, progress: Float) {
    if (!downloading) return
    val colors = SyncTheme.colors
    if (progress >= 0f) {
        LinearProgressIndicator(
            progress = { progress },
            color = colors.onScrim,
            trackColor = colors.onScrimMuted.copy(alpha = 0.3f),
            modifier = Modifier.width(DownloadBarWidth),
        )
    } else {
        LinearProgressIndicator(
            color = colors.onScrim,
            trackColor = colors.onScrimMuted.copy(alpha = 0.3f),
            modifier = Modifier.width(DownloadBarWidth),
        )
    }
}

@Composable
private fun CenterMessage(
    title: String,
    body: String?,
    dim: Boolean,
    spinner: Boolean = false,
    icon: ImageVector? = null,
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
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = colors.onScrim, modifier = Modifier.size(CenterIconSize))
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
