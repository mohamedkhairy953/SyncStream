package com.syncstream.ui.master

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncstream.ui.components.BannerTone
import com.syncstream.ui.components.ConfirmDialog
import com.syncstream.ui.components.EmptyState
import com.syncstream.ui.components.InfoBanner
import com.syncstream.ui.components.PrimaryButton
import com.syncstream.ui.components.RoomChip
import com.syncstream.ui.components.SecondaryButton
import com.syncstream.ui.components.VideoThumbnail
import com.syncstream.ui.theme.SyncSpacing

/**
 * Host "Pick a video" screen. Binds [com.syncstream.service.MasterStreamingService] (via the
 * ViewModel), which makes the room live so guests can join while the host chooses. Choosing a
 * recent video or a file from the picker starts playback and calls [onVideoChosen]; leaving asks
 * for confirmation and ends the room via [onEndRoom].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostPickScreen(
    viewModel: MasterViewModel,
    onVideoChosen: () -> Unit,
    onEndRoom: () -> Unit,
) {
    val pin by viewModel.pin.collectAsStateWithLifecycle()
    val endpoint by viewModel.endpoint.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val thermalWarning by viewModel.thermalWarning.collectAsStateWithLifecycle()
    val notificationsDenied by viewModel.notificationsDenied.collectAsStateWithLifecycle()
    val recentVideos by viewModel.recentVideos.collectAsStateWithLifecycle()

    var showInvite by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }

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

    // ---- File picker ----
    val openDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            viewModel.onMediaPicked(it)
            viewModel.startStreaming()
            onVideoChosen()
        }
    }
    val browse = { openDocumentLauncher.launch(arrayOf("video/*")) }

    BackHandler { confirmEnd = true }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { confirmEnd = true }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "End room")
                    }
                },
                title = { Text("Pick a video") },
                actions = {
                    if (pin.isNotEmpty()) {
                        RoomChip(pin, onClick = { showInvite = true })
                    } else {
                        Text(
                            "Going live…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SyncSpacing.l),
            horizontalArrangement = Arrangement.spacedBy(SyncSpacing.m),
            verticalArrangement = Arrangement.spacedBy(SyncSpacing.m),
        ) {
            if (notificationsDenied) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InfoBanner(
                        title = "Notifications are disabled",
                        body = "The streaming service runs in the foreground but cannot show its status notification. Grant the permission or end the room.",
                        tone = BannerTone.Error,
                    ) {
                        PrimaryButton("Grant", {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        })
                        SecondaryButton("End room", onEndRoom)
                    }
                }
            }

            if (thermalWarning) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    InfoBanner(
                        title = "Device is hot",
                        body = "Streaming reduced to 960×540 at 24 fps",
                        tone = BannerTone.Warning,
                    )
                }
            }

            if (recentVideos.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        Icons.Filled.VideoLibrary,
                        "No recent videos",
                        "Pick a video from this device to start the room.",
                        action = { SecondaryButton("Browse device…", { browse() }, icon = Icons.Filled.VideoLibrary) },
                    )
                }
            } else {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text("Recent", style = MaterialTheme.typography.labelLarge)
                }
                items(recentVideos, key = { it.uri }) { recent ->
                    VideoThumbnail(recent.name, null, onClick = {
                        viewModel.playRecent(recent)
                        viewModel.startStreaming()
                        onVideoChosen()
                    })
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SecondaryButton("Browse device…", { browse() }, icon = Icons.Filled.VideoLibrary)
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Your room is live. Guests can join while you choose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showInvite) {
        InviteSheet(
            endpoint = endpoint,
            pin = pin,
            guests = clients.map { it.deviceName },
            // No video is chosen yet, so there is nothing to "Play for everyone".
            playing = true,
            onPlayForEveryone = {},
            onEndRoom = { showInvite = false; onEndRoom() },
            onDismiss = { showInvite = false },
        )
    }

    if (confirmEnd) {
        ConfirmDialog(
            title = "End room?",
            body = "Guests will be disconnected.",
            confirmText = "End room",
            onConfirm = { confirmEnd = false; onEndRoom() },
            onDismiss = { confirmEnd = false },
            destructive = true,
        )
    }
}
