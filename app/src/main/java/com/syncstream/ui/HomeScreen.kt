package com.syncstream.ui

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.syncstream.ui.client.NearbyRoomsSection
import com.syncstream.ui.client.RoomTarget
import com.syncstream.ui.components.PrimaryButton
import com.syncstream.ui.components.SecondaryButton
import com.syncstream.ui.theme.SyncSpacing

/**
 * Start destination. Live "Rooms nearby" list on top; Host a room / Scan QR / Enter code within
 * thumb reach at the bottom. Scan QR is hidden on devices without a camera (e.g. Android TV),
 * where Enter code then takes the full width.
 */
@Composable
fun HomeScreen(
    onHost: () -> Unit,
    onJoinRoom: (RoomTarget) -> Unit,
    onScan: () -> Unit,
    onEnterCode: () -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(SyncSpacing.l),
        verticalArrangement = Arrangement.spacedBy(SyncSpacing.m),
    ) {
        Text("SyncStream", style = MaterialTheme.typography.headlineMedium)
        NearbyRoomsSection(onJoin = onJoinRoom, modifier = Modifier.weight(1f))
        PrimaryButton(
            text = "Host a room",
            onClick = onHost,
            modifier = Modifier.fillMaxWidth(),
            large = true,
            icon = Icons.Filled.Cast,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
            if (hasCamera) {
                SecondaryButton(
                    text = "Scan QR",
                    onClick = onScan,
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.QrCodeScanner,
                )
            }
            SecondaryButton(
                text = "Enter code",
                onClick = onEnterCode,
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Keyboard,
            )
        }
    }
}
