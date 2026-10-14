package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.syncstream.ui.theme.SyncShapes
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncTheme

enum class StatusTone { Neutral, Success, Warning }

private val PillIconSize = 16.dp
private val DotSize = 6.dp

/** "Room 4827" pill for the player top bar; opens the invite sheet. */
@Composable
fun RoomChip(code: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .clip(SyncShapes.pill)
            .background(SyncTheme.colors.scrimPill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SyncSpacing.m, vertical = SyncSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyncSpacing.xs),
    ) {
        Icon(
            Icons.Filled.QrCode2,
            contentDescription = null,
            tint = SyncTheme.colors.onScrim,
            modifier = Modifier.size(PillIconSize),
        )
        Text("Room $code", style = MaterialTheme.typography.labelLarge, color = SyncTheme.colors.onScrim)
    }
}

/** Status pill for use over video. */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Neutral,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = SyncTheme.colors
    val accent: Color = when (tone) {
        StatusTone.Neutral -> colors.onScrim
        StatusTone.Success -> colors.success
        StatusTone.Warning -> colors.warning
    }
    val background = if (tone == StatusTone.Neutral) colors.scrimPill else accent.copy(alpha = 0.18f)
    val clickable = if (onClick != null) Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onClick) else Modifier
    Row(
        modifier
            .clip(SyncShapes.pill)
            .background(background)
            .then(clickable)
            .padding(horizontal = SyncSpacing.m, vertical = SyncSpacing.xs + SyncSpacing.xs / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SyncSpacing.xs + SyncSpacing.xs / 2),
    ) {
        if (tone != StatusTone.Neutral) {
            Box(
                Modifier
                    .size(DotSize)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(PillIconSize))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

@Composable
private fun PillsPreviewContent() = PreviewVideoHost {
    Row(horizontalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
        RoomChip("4827", {})
        StatusPill("Synced · 12 ms", tone = StatusTone.Success)
        StatusPill("Syncing…", tone = StatusTone.Warning)
        StatusPill("Sound on", icon = Icons.AutoMirrored.Filled.VolumeUp, onClick = {})
    }
}

@Preview(name = "light") @Composable private fun PillsPreviewLight() = PillsPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun PillsPreviewDark() = PillsPreviewContent()
