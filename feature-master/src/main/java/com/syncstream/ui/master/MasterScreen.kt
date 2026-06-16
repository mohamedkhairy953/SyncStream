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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import com.syncstream.discovery.AdvertiseState
import com.syncstream.signaling.ClientHandle
import com.syncstream.core.PeerState
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Master setup surface (pre-player). Binds [com.syncstream.service.MasterStreamingService] (via
 * the ViewModel) and observes its [androidx.lifecycle.Lifecycle]-aware StateFlows. Provides the SAF
 * pickers (primary `OpenDocument(video MIME)` + secondary `PickVisualMedia(VideoOnly)`), the local
 * 16:9 preview (`SurfaceViewRenderer` added once, sink removed THEN released in the
 * DisposableEffect), the connected-client list, the thermal chip and the notification permission
 * banner. Transport controls live in [MasterPlayerScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterScreen(
    viewModel: MasterViewModel,
    onStartStreaming: () -> Unit,
    onExit: () -> Unit,
) {
    val pin by viewModel.pin.collectAsStateWithLifecycle()
    val sessionLabel by viewModel.sessionLabel.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val advertise by viewModel.advertise.collectAsStateWithLifecycle()
    val thermalWarning by viewModel.thermalWarning.collectAsStateWithLifecycle()
    val notificationsDenied by viewModel.notificationsDenied.collectAsStateWithLifecycle()
    val selectedUri by viewModel.selectedUri.collectAsStateWithLifecycle()

    // Bind the service while the screen is in the foreground. NOTE: we intentionally do NOT unbind
    // on dispose. This composable leaves composition when navigating forward to MasterPlayerScreen,
    // and unbinding there would null out the shared service reference — leaving the player stuck on
    // "Preparing…" (eglContext/videoSource read null). The ViewModel is scoped to the masterGraph
    // back-stack entry, so unbind() runs in onCleared() when the whole master flow is popped.
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.bind() }

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

            // ---- Streaming controls ----
            // The session goes live automatically on entering this screen (see MasterViewModel),
            // so the master is discoverable/joinable before any video is picked. "Start streaming"
            // navigates to the full-screen player once a video has been selected; "Stop hosting"
            // ends the session and leaves.
            if (!streaming) {
                Text(
                    "Going live…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (selectedUri == null) {
                    Text(
                        "Clients can already find and join this master. Pick a video to start playback.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = onStartStreaming,
                    enabled = selectedUri != null,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start streaming") }
                OutlinedButton(
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop hosting") }
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

