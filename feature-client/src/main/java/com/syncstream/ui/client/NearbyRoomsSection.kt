package com.syncstream.ui.client

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syncstream.discovery.DiscoveredService
import com.syncstream.discovery.DiscoveryState
import com.syncstream.discovery.NsdDiscoverer
import com.syncstream.ui.components.BannerTone
import com.syncstream.ui.components.EmptyState
import com.syncstream.ui.components.InfoBanner
import com.syncstream.ui.components.RoomCard
import com.syncstream.ui.theme.SyncSpacing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Live mDNS browse for Home's "Rooms nearby" list. Started/stopped by [NearbyRoomsSection]. */
class NearbyRoomsViewModel(app: Application) : AndroidViewModel(app) {

    private val discoverer = NsdDiscoverer(app)

    val rooms: StateFlow<List<RoomTarget>> = discoverer.services
        .map { services -> services.map { it.toRoomTarget() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val failed: StateFlow<Boolean> = discoverer.state
        .map { it is DiscoveryState.Failed }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun start() = discoverer.start()
    fun stop() = discoverer.stop()

    override fun onCleared() {
        discoverer.stop()
        super.onCleared()
    }
}

/** Maps a discovered master to a dialable room, labelled by its TXT label (or service name). */
internal fun DiscoveredService.toRoomTarget() = RoomTarget(host, port, label ?: serviceName)

/** Home's live "Rooms nearby" list. Starts discovery while composed and visible (LifecycleStartEffect). */
@Composable
fun NearbyRoomsSection(
    onJoin: (RoomTarget) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NearbyRoomsViewModel = viewModel(),
) {
    LifecycleStartEffect(viewModel) {
        viewModel.start()
        onStopOrDispose { viewModel.stop() }
    }
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
        Text("Rooms nearby", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        when {
            rooms.isNotEmpty() -> rooms.forEach { room ->
                RoomCard(room.label, "Tap to join", onJoin = { onJoin(room) })
            }
            failed -> InfoBanner(
                title = "Couldn't search this network",
                body = "Use Enter code instead.",
                tone = BannerTone.Warning,
            )
            else -> EmptyState(
                Icons.Filled.WifiFind,
                "No rooms nearby yet",
                "Make sure you're on the same Wi-Fi as the host.",
            )
        }
    }
}
