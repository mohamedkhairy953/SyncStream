package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.syncstream.ui.theme.SyncTheme
import kotlin.math.abs

private val AvatarSize = 24.dp
private val AvatarOverlap = (-6).dp
private const val MAX_SHOWN = 4

/** 24 dp circle with the guest's initial; the color is derived deterministically from the name. */
@Composable
fun GuestAvatar(name: String, modifier: Modifier = Modifier) {
    val palette = listOf(MaterialTheme.colorScheme.secondary, SyncTheme.colors.warning, MaterialTheme.colorScheme.primary)
    val letter = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    AvatarCircle(letter, palette[abs(name.hashCode()) % 3], modifier)
}

@Composable
private fun AvatarCircle(text: String, background: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(AvatarSize)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.surface)
    }
}

/** Overlapping avatars, at most 4 shown, then a "+N" avatar. */
@Composable
fun GuestAvatarRow(names: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(AvatarOverlap), verticalAlignment = Alignment.CenterVertically) {
        names.take(MAX_SHOWN).forEach { GuestAvatar(it) }
        if (names.size > MAX_SHOWN) {
            AvatarCircle("+${names.size - MAX_SHOWN}", MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GuestAvatarsPreviewContent() = PreviewVideoHost {
    GuestAvatarRow(listOf("Sam", "Alex", "Maya", "Noor", "Jo", "Li"))
}

@Preview(name = "light") @Composable private fun GuestAvatarsPreviewLight() = GuestAvatarsPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun GuestAvatarsPreviewDark() = GuestAvatarsPreviewContent()
