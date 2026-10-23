package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.syncstream.ui.theme.SyncTheme

/**
 * Previous / "n of total" / next for a paged presentation (PDF), styled for the player scrim.
 * [page] is 0-based; the buttons disable at either end.
 */
@Composable
fun PageControls(
    page: Int,
    pageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SyncTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious, enabled = page > 0) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateBefore,
                contentDescription = "Previous page",
                tint = if (page > 0) colors.onScrim else colors.onScrimMuted,
            )
        }
        Text(
            "${page + 1} / $pageCount",
            style = MaterialTheme.typography.labelLarge,
            color = colors.onScrim,
        )
        IconButton(onClick = onNext, enabled = page < pageCount - 1) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = "Next page",
                tint = if (page < pageCount - 1) colors.onScrim else colors.onScrimMuted,
            )
        }
    }
}

@Composable
private fun PageControlsPreviewContent() = PreviewVideoHost {
    PageControls(page = 2, pageCount = 12, onPrevious = {}, onNext = {})
}

@Preview(name = "light") @Composable private fun PageControlsPreviewLight() = PageControlsPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun PageControlsPreviewDark() = PageControlsPreviewContent()
