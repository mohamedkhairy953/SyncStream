package com.syncstream.ui.master

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syncstream.core.JoinLink
import com.syncstream.ui.components.ConfirmDialog
import com.syncstream.ui.components.GuestAvatar
import com.syncstream.ui.components.JoinQr
import com.syncstream.ui.components.PrimaryButton
import com.syncstream.ui.components.SecondaryButton
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncTextStyles

/**
 * Invite sheet: join QR, room code, guest list, manual-connect fallback and End room.
 *
 * [endpoint] is the master's `"ip:port"` (from `MasterViewModel.endpoint`); null while the room is
 * not live yet. [playing] hides the "Play for everyone" button once playback has started.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InviteSheet(
    endpoint: String?,
    pin: String,
    guests: List<String>,
    playing: Boolean,
    onPlayForEveryone: () -> Unit,
    onEndRoom: () -> Unit,
    onDismiss: () -> Unit,
) {
    val host = remember(endpoint) { endpoint?.substringBeforeLast(':', "")?.takeIf { it.isNotBlank() } }
    val port = remember(endpoint) { endpoint?.substringAfterLast(':', "")?.toIntOrNull() }
    val ready = host != null && port != null && pin.isNotBlank()
    val joinLink = remember(host, port, pin) { if (ready) JoinLink.build(host!!, port!!, pin) else null }
    val manualCode = remember(host, port, pin) { if (ready) JoinLink.buildCode(host!!, port!!, pin) else null }

    var confirmEnd by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SyncSpacing.xl)
                .padding(bottom = SyncSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(SyncSpacing.l),
        ) {
            Text("Invite guests", style = MaterialTheme.typography.titleLarge)

            Row(
                horizontalArrangement = Arrangement.spacedBy(SyncSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (joinLink != null) {
                    JoinQr(joinLink)
                } else {
                    CircularProgressIndicator(Modifier.size(48.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(SyncSpacing.xs)) {
                    Text(
                        "Room code",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(pin.ifEmpty { "····" }, style = SyncTextStyles.code)
                    Text(
                        "Rooms nearby show this room automatically",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
                Text("Guests · ${guests.size} of 4", style = MaterialTheme.typography.labelLarge)
                if (guests.isEmpty()) {
                    Text(
                        "Nobody has joined yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    guests.forEach { name ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(SyncSpacing.m),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GuestAvatar(name)
                            Text(name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            if (manualCode != null) {
                SelectionContainer {
                    Text(
                        "Trouble connecting? Code: $manualCode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!playing) {
                PrimaryButton("Play for everyone", onPlayForEveryone, Modifier.fillMaxWidth(), large = true)
            }
            SecondaryButton("End room", { confirmEnd = true }, Modifier.fillMaxWidth(), large = true)
        }
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
