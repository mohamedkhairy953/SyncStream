package com.syncstream.ui.client

import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syncstream.signaling.ConnectionState
import com.syncstream.signaling.PeerState
import com.syncstream.sync.PlayheadInfo
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Full-bleed client viewer. Renders the master's video track into a [SurfaceViewRenderer]
 * (initialized exactly once with the shared `EglBase.eglBaseContext`; the [DisposableEffect]
 * removes the sink THEN releases the renderer), overlaid with the synchronized playhead, a colour
 * graded latency badge, a mute toggle (DEFAULT MUTED) and connection / ended overlays.
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

    // Dial on first composition using the PIN handed over from DiscoveryScreen.
    LaunchedEffect(host, port) {
        viewModel.connect(host, port, PendingConnection.pin)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // ---- Video surface ----
        val renderer = remember {
            // Created here so the same instance is reused across recompositions; init/sink/release
            // are driven by the DisposableEffect below.
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

        // Renderer release effect. Declared FIRST so Compose (which disposes effects in reverse
        // declaration order) runs this LAST — i.e. the sink-removal effect below disposes first,
        // then the renderer is released. This enforces the contract order: remove sink THEN release.
        DisposableEffect(Unit) {
            onDispose {
                renderer.view?.release()
                renderer.view = null
            }
        }

        // Attach the remote track to the renderer as it becomes available; on dispose (track change
        // or leaving composition) the sink is removed before the release effect above runs.
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

        // ---- Overlays ----
        TopOverlay(
            latencyMs = latencyMs,
            muted = muted,
            onToggleMute = { viewModel.setMuted(!muted) },
            onDisconnect = {
                viewModel.disconnect()
                onExit()
            },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        PlayheadOverlay(
            playhead = playhead,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

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
    onToggleMute: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LatencyBadge(latencyMs)
        Spacer(Modifier.weight(1f))
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

@Composable
private fun PlayheadOverlay(playhead: PlayheadInfo, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            if (playhead.playing) "PLAYING" else "PAUSED",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "${formatMs(playhead.positionMs)} / ${formatMs(playhead.durationMs)}",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
        )
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

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
