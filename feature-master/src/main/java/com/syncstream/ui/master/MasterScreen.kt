package com.syncstream.ui.master

import android.Manifest
import android.os.Build
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syncstream.discovery.AdvertiseState
import com.syncstream.signaling.ClientHandle
import com.syncstream.core.PeerState
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Master control surface. Binds [com.syncstream.service.MasterStreamingService] (via the
 * ViewModel) and observes its [androidx.lifecycle.Lifecycle]-aware StateFlows. Provides the SAF
 * pickers (primary `OpenDocument(video MIME)` + secondary `PickVisualMedia(VideoOnly)`), the local
 * preview (`SurfaceViewRenderer` added once, sink removed THEN released in the DisposableEffect),
 * transport controls, loop toggle, the connected-client list, the thermal chip and the notification
 * permission banner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterScreen(
    onExit: () -> Unit,
    viewModel: MasterViewModel = viewModel(),
) {
    val bound by viewModel.bound.collectAsStateWithLifecycle()
    val pin by viewModel.pin.collectAsStateWithLifecycle()
    val sessionLabel by viewModel.sessionLabel.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val advertise by viewModel.advertise.collectAsStateWithLifecycle()
    val thermalWarning by viewModel.thermalWarning.collectAsStateWithLifecycle()
    val notificationsDenied by viewModel.notificationsDenied.collectAsStateWithLifecycle()
    val selectedUri by viewModel.selectedUri.collectAsStateWithLifecycle()

    // Bind the service while the screen is in the foreground.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.bind() }
    DisposableEffect(Unit) { onDispose { viewModel.unbind() } }

    // POST_NOTIFICATIONS runtime request (API 33+).
    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result reflected via service.notificationsDenied after start */ }

    // ---- File pickers ----
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::onMediaPicked) }

    val pickVisualMediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::onMediaPicked) }

    val streaming = pin.isNotEmpty()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Master") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (notificationsDenied) {
                NotificationBanner(
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onStop = { viewModel.stopStreaming() },
                )
            }

            if (thermalWarning) ThermalChip()

            SessionHeader(pin = pin, sessionLabel = sessionLabel, advertise = advertise)

            PreviewBox(viewModel = viewModel, active = streaming)

            // ---- Source pickers ----
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { openDocumentLauncher.launch(arrayOf("video/*")) },
                    modifier = Modifier.weight(1f),
                ) { Text("Pick video (SAF)") }
                OutlinedButton(
                    onClick = {
                        pickVisualMediaLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Pick video (Gallery)") }
            }

            if (selectedUri != null) {
                Text(
                    "Selected: ${selectedUri?.lastPathSegment ?: selectedUri}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- Start / stop ----
            if (!streaming) {
                Button(
                    onClick = { viewModel.startStreaming() },
                    enabled = bound && selectedUri != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start streaming") }
            } else {
                TransportControls(
                    playing = playback.playing,
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    onPlay = viewModel::play,
                    onPause = viewModel::pause,
                    onSeek = viewModel::seekTo,
                    onLoopChanged = viewModel::setLoop,
                )
                OutlinedButton(
                    onClick = {
                        viewModel.stopStreaming()
                        onExit()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop streaming") }
            }

            ClientList(clients = clients)
        }
    }
}

@Composable
private fun SessionHeader(pin: String, sessionLabel: String, advertise: AdvertiseState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("PIN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (pin.isEmpty()) "— — — —" else pin,
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (sessionLabel.isEmpty()) "Not advertising yet" else sessionLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                advertiseText(advertise),
                style = MaterialTheme.typography.bodySmall,
                color = when (advertise) {
                    is AdvertiseState.Failed -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun advertiseText(state: AdvertiseState): String = when (state) {
    AdvertiseState.Idle -> "Discovery: idle"
    AdvertiseState.Registering -> "Discovery: registering…"
    is AdvertiseState.Registered -> "Discovery: visible as '${state.serviceName}'"
    is AdvertiseState.Failed -> "Discovery failed (code ${state.errorCode}); clients can connect via manual IP"
}

@Composable
private fun PreviewBox(viewModel: MasterViewModel, active: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (!active) {
            Text("Preview appears once streaming starts", color = Color.White.copy(alpha = 0.7f))
            return@Box
        }
        val eglContext = viewModel.eglContext
        val source = viewModel.videoSource
        if (eglContext == null || source == null) {
            Text("Preparing preview…", color = Color.White.copy(alpha = 0.7f))
            return@Box
        }

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
                    source.addPreviewSink(this)
                }
            },
        )

        // Contract teardown: remove the sink THEN release the renderer.
        DisposableEffect(source) {
            onDispose {
                holder.view?.let { v ->
                    source.removePreviewSink(v)
                    v.release()
                }
                holder.view = null
            }
        }
    }
}

@Composable
private fun TransportControls(
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onLoopChanged: (Boolean) -> Unit,
) {
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }
    var loop by remember { mutableStateOf(false) }

    val duration = durationMs.coerceAtLeast(0L)
    val sliderMax = duration.coerceAtLeast(1L).toFloat()
    val sliderValue = if (scrubbing) scrubValue else positionMs.toFloat().coerceIn(0f, sliderMax)

    Column(Modifier.fillMaxWidth()) {
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
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(sliderValue.toLong()), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text(formatMs(duration), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledIconButton(onClick = { if (playing) onPause() else onPlay() }) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                )
            }
            Spacer(Modifier.weight(1f))
            Text("Loop", style = MaterialTheme.typography.labelLarge)
            Switch(
                checked = loop,
                onCheckedChange = {
                    loop = it
                    onLoopChanged(it)
                },
            )
        }
    }
}

@Composable
private fun ClientList(clients: List<ClientHandle>) {
    Text(
        "Clients (${clients.size}/4)",
        style = MaterialTheme.typography.titleMedium,
    )
    if (clients.isEmpty()) {
        Text(
            "No clients connected yet. Share the PIN above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .height((clients.size * 64).dp.coerceAtMost(256.dp)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(clients, key = { it.sessionId }) { handle ->
            ClientRow(handle)
        }
    }
}

@Composable
private fun ClientRow(handle: ClientHandle) {
    val state by handle.fsm.state.collectAsStateWithLifecycle()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(handle.deviceName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    handle.sessionId.take(8),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PeerStateChip(state)
        }
    }
}

@Composable
private fun PeerStateChip(state: PeerState) {
    val (label, color) = when (state) {
        PeerState.NEW -> "New" to MaterialTheme.colorScheme.outline
        PeerState.OFFERING -> "Offering" to Color(0xFFF9A825)
        PeerState.CONNECTED -> "Connected" to Color(0xFF2E7D32)
        PeerState.FAILED -> "Reconnecting" to Color(0xFFC62828)
        PeerState.CLOSED -> "Closed" to MaterialTheme.colorScheme.outline
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = color,
            disabledContainerColor = color.copy(alpha = 0.12f),
        ),
    )
}

@Composable
private fun ThermalChip() {
    AssistChip(
        onClick = {},
        enabled = false,
        leadingIcon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        label = { Text("Thermal throttling — reduced to 960×540@24") },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = MaterialTheme.colorScheme.onErrorContainer,
            disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
            disabledLeadingIconContentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    )
}

@Composable
private fun NotificationBanner(onGrant: () -> Unit, onStop: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Notifications are disabled",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "The streaming service runs in the foreground but cannot show its status notification. Grant the permission or stop the session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrant) { Text("Grant") }
                OutlinedButton(onClick = onStop) { Text("Stop") }
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
