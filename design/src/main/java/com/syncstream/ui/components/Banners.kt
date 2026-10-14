package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncTheme

enum class BannerTone { Info, Warning, Error }

/** Inline notice with title, body and an optional row of [actions]. */
@Composable
fun InfoBanner(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.Info,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val container: Color
    val content: Color
    val icon: ImageVector
    when (tone) {
        BannerTone.Info -> { container = scheme.primaryContainer; content = scheme.onPrimaryContainer; icon = Icons.Filled.Info }
        BannerTone.Warning -> {
            container = SyncTheme.colors.warning.copy(alpha = 0.16f)
            content = SyncTheme.colors.warning
            icon = Icons.Filled.Warning
        }
        BannerTone.Error -> { container = scheme.errorContainer; content = scheme.onErrorContainer; icon = Icons.Filled.ErrorOutline }
    }
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container, contentColor = content) {
        Row(Modifier.padding(SyncSpacing.m), horizontalArrangement = Arrangement.spacedBy(SyncSpacing.m)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(SyncSpacing.xs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.padding(top = SyncSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(SyncSpacing.s),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
    }
}

@Composable
private fun BannersPreviewContent() = PreviewHost {
    Column(verticalArrangement = Arrangement.spacedBy(SyncSpacing.m)) {
        InfoBanner("Heads up", "Guests can join while you choose.")
        InfoBanner("Device is hot", "Streaming reduced to 960×540 at 24 fps", tone = BannerTone.Warning)
        InfoBanner("Notifications off", "Streaming may stop in the background.", tone = BannerTone.Error) {
            PrimaryButton("Grant", {})
            SecondaryButton("End room", {})
        }
    }
}

@Preview(name = "light") @Composable private fun BannersPreviewLight() = BannersPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun BannersPreviewDark() = BannersPreviewContent()
