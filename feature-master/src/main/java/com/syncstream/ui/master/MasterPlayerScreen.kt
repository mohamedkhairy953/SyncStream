package com.syncstream.ui.master

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncstream.core.formatByteSize
import com.syncstream.ui.components.GuestAvatarRow
import com.syncstream.ui.components.MediaKinds
import com.syncstream.ui.components.PageControls
import com.syncstream.ui.components.PlayerOverlayScaffold
import com.syncstream.ui.components.RoomChip
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

/**
 * Full-screen host video player built on [PlayerOverlayScaffold]. Shares the same [MasterViewModel]
 * instance as [HostPickScreen] (scoped to the masterGraph back-stack entry) so the streaming
 * session keeps running while this screen is shown.
 *
 * Interaction model (standard video-player behaviour):
 * - Overlays auto-hide after a few seconds of no interaction while playing, and stay visible while
 *   paused.
 * - Tapping while overlays are hidden reveals them (without toggling playback); tapping while they
 *   are shown toggles play/pause. Any interaction restarts the auto-hide timer.
 * - Immersive display (system bars hidden, screen kept awake); hardware-back returns to the video
 *   picker without ending the room.
 * - The invite sheet opens automatically the first time the player opens with no guests and
 *   nothing playing.
 */
@Composable
fun MasterPlayerScreen(
    viewModel: MasterViewModel,
    onBack: () -> Unit,
    onEndRoom: () -> Unit,
) {
    val bound by viewModel.bound.collectAsStateWithLifecycle()
    val pin by viewModel.pin.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val selectedName by viewModel.selectedName.collectAsStateWithLifecycle()
    val selectedSize by viewModel.selectedSize.collectAsStateWithLifecycle()
    val selectedBitmap by viewModel.selectedBitmap.collectAsStateWithLifecycle()
    val mediaPage by viewModel.mediaPage.collectAsStateWithLifecycle()
    val mediaPageCount by viewModel.mediaPageCount.collectAsStateWithLifecycle()
    val isTimedMedia = MediaKinds.isTimed(playback.kind)

    // Pinch-zoom/pan state for photos and PDF pages; a new item or page starts unzoomed.
    var zoom by remember(selectedName, mediaPage) { mutableFloatStateOf(1f) }
    var pan by remember(selectedName, mediaPage) { mutableStateOf(Offset.Zero) }
    val thermalWarning by viewModel.thermalWarning.collectAsStateWithLifecycle()
    val loop by viewModel.loop.collectAsStateWithLifecycle()
    val speed by viewModel.speed.collectAsStateWithLifecycle()
    val endpoint by viewModel.endpoint.collectAsStateWithLifecycle()

    var showInvite by remember { mutableStateOf(false) }
    var autoInviteShown by rememberSaveable { mutableStateOf(false) }

    // Self-heal: if the service somehow isn't bound, bind it (idempotent). Reading the bound state
    // below makes eglContext/videoSource (plain getters) refresh once the service connects.
    LaunchedEffect(Unit) { viewModel.bind() }
    @Suppress("UNUSED_EXPRESSION") bound
    val eglContext = viewModel.eglContext
    val videoSource = viewModel.videoSource

    // Auto-open the invite on first composition when nobody has joined and nothing is playing.
    LaunchedEffect(Unit) {
        if (!autoInviteShown && clients.isEmpty() && !playback.playing) {
            autoInviteShown = true
            showInvite = true
        }
    }

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

    // ---- Hardware back -> video picker, keep hosting ----
    BackHandler { onBack() }

    // Reads the live state at tap time (not a value captured into a long-lived lambda).
    val togglePlayPause = {
        val pb = viewModel.playback.value
        when {
            pb.playing -> viewModel.pause()
            pb.ended -> { viewModel.seekTo(0L); viewModel.play() }
            else -> viewModel.play()
        }
    }

    // Overlays stay up while paused; while playing they auto-hide, restarted by every poke().
    val overlay = rememberOverlayVisibility(playback.playing || !isTimedMedia)

    // Locally interpolated position so the seek bar moves smoothly between playback updates.
    val duration = playback.durationMs.coerceAtLeast(0L)
    var ticked by remember { mutableLongStateOf(playback.positionMs) }
    LaunchedEffect(playback.positionMs, playback.playing, duration, speed) {
        ticked = playback.positionMs
        if (playback.playing && duration > 0) {
            while (true) {
                delay(500)
                ticked = (ticked + (500L * speed).toLong()).coerceAtMost(duration)
            }
        }
    }

    PlayerOverlayScaffold(
        visible = overlay.visible,
        // Hidden overlays: tap reveals them. Shown overlays: tap toggles play/pause for timed media.
        // Either way, restart the auto-hide timer.
        onTap = {
            if (overlay.visible && isTimedMedia) togglePlayPause()
            overlay.poke()
        },
        onDoubleTap = {
            overlay.poke()
            if (zoom > 1.05f) {
                zoom = 1f
                pan = Offset.Zero
            }
        },
        onTransform = { zoomChange, panChange ->
            val newZoom = (zoom * zoomChange).coerceIn(1f, 5f)
            zoom = newZoom
            pan = if (newZoom <= 1.01f) Offset.Zero else pan + panChange
        },
        modifier = Modifier.background(Color.Black),
        video = {
            if (playback.kind == MediaKinds.IMAGE || playback.kind == MediaKinds.PDF) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val bmp = selectedBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = selectedName,
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
                    } else {
                        CircularProgressIndicator(color = SyncTheme.colors.onScrim)
                    }
                }
            } else if (playback.kind == MediaKinds.AUDIO || playback.kind == MediaKinds.FILE) {
                // No video surface: a title panel on black (guests see the same, plus their
                // download progress for a file).
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            MediaKinds.icon(playback.kind),
                            contentDescription = null,
                            tint = SyncTheme.colors.onScrim,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.height(SyncSpacing.m))
                        Text(
                            selectedName ?: if (playback.kind == MediaKinds.AUDIO) "Now playing" else "Shared file",
                            color = SyncTheme.colors.onScrim,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = SyncSpacing.l),
                        )
                        if (playback.kind == MediaKinds.FILE) {
                            Spacer(Modifier.height(SyncSpacing.xs))
                            Text(
                                "${formatByteSize(selectedSize)} · guests are receiving it and can save it",
                                color = SyncTheme.colors.onScrimMuted,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = SyncSpacing.l),
                            )
                        }
                    }
                }
            } else if (eglContext == null || videoSource == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Preparing stream…",
                        color = SyncTheme.colors.onScrim,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } else {
                // Aspect ratio of the actual decoded video (w/h after display rotation). 0 until the
                // first frame's resolution is known. Reported by the renderer's onFrameResolutionChanged.
                var videoAspect by remember { mutableFloatStateOf(0f) }
                val holder = remember { object { var view: SurfaceViewRenderer? = null } }

                BoxWithConstraints(Modifier.fillMaxSize()) {
                    // Letterbox/pillarbox: size the surface to the video aspect and centre it on black,
                    // always fitting inside the current screen bounds (recomputed on rotation).
                    val screenAspect = maxWidth / maxHeight
                    val surfaceModifier = when {
                        videoAspect <= 0f -> Modifier.fillMaxSize()
                        videoAspect >= screenAspect -> Modifier.fillMaxWidth().aspectRatio(videoAspect)
                        else -> Modifier.fillMaxHeight().aspectRatio(videoAspect)
                    }
                    AndroidView(
                        modifier = surfaceModifier.align(Alignment.Center),
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
                                                videoAspect =
                                                    if (rotation % 180 == 0) w.toFloat() / h
                                                    else h.toFloat() / w
                                            }
                                        }
                                    },
                                )
                                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                                setEnableHardwareScaler(false)
                                holder.view = this
                                videoSource.addPreviewSink(this)
                            }
                        },
                    )
                }
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
        },
        topBar = {
            IconButton(onClick = { overlay.poke(); onBack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = SyncTheme.colors.onScrim,
                )
            }
            if (pin.isNotEmpty()) {
                RoomChip(pin, onClick = { overlay.poke(); showInvite = true })
            }
            if (thermalWarning) {
                StatusPill("Reduced quality", tone = StatusTone.Warning, icon = Icons.Filled.Warning)
            }
            Spacer(Modifier.weight(1f))
            GuestAvatarRow(clients.map { it.deviceName })
        },
        bottomBar = {
            if (isTimedMedia) {
                SeekBar(
                    positionMs = ticked,
                    durationMs = duration,
                    onSeek = { overlay.poke(); viewModel.seekTo(it) },
                    onScrub = overlay::poke,
                )
                TransportControls(
                    playing = playback.playing,
                    onPlayPause = { overlay.poke(); togglePlayPause() },
                    onRewind = { overlay.poke(); viewModel.seekTo((playback.positionMs - 10_000L).coerceAtLeast(0L)) },
                    onForward = { overlay.poke(); viewModel.seekTo(playback.positionMs + 10_000L) },
                    extra = {
                        IconButton(onClick = { overlay.poke(); viewModel.setLoop(!loop) }) {
                            Icon(
                                Icons.Filled.Repeat,
                                contentDescription = "Loop",
                                tint = if (loop) MaterialTheme.colorScheme.primary else SyncTheme.colors.onScrim,
                            )
                        }
                        SpeedMenu(speed, onSetSpeed = { overlay.poke(); viewModel.setSpeed(it) })
                        IconButton(onClick = { overlay.poke(); onBack() }) {
                            Icon(
                                Icons.Filled.VideoLibrary,
                                contentDescription = "Select another media",
                                tint = SyncTheme.colors.onScrim,
                            )
                        }
                    },
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        selectedName ?: "Shared media",
                        color = SyncTheme.colors.onScrim,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = SyncSpacing.m),
                    )
                    if (playback.kind == MediaKinds.PDF && mediaPageCount > 0) {
                        PageControls(
                            page = mediaPage,
                            pageCount = mediaPageCount,
                            onPrevious = { overlay.poke(); viewModel.previousPage() },
                            onNext = { overlay.poke(); viewModel.nextPage() },
                        )
                    }
                    IconButton(onClick = { overlay.poke(); onBack() }) {
                        Icon(
                            Icons.Filled.VideoLibrary,
                            contentDescription = "Select another media",
                            tint = SyncTheme.colors.onScrim,
                        )
                    }
                }
            }
        },
    )

    if (showInvite) {
        InviteSheet(
            endpoint = endpoint,
            pin = pin,
            guests = clients.map { it.deviceName },
            playing = playback.playing || !isTimedMedia,
            onPlayForEveryone = { viewModel.play(); showInvite = false },
            onEndRoom = { showInvite = false; onEndRoom() },
            onDismiss = { showInvite = false },
        )
    }
}
