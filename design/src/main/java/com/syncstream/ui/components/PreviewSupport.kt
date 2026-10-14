package com.syncstream.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncStreamTheme

/** Preview wrapper: themed surface following the preview's uiMode. */
@Composable
internal fun PreviewHost(content: @Composable () -> Unit) {
    SyncStreamTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(SyncSpacing.l)) { content() }
        }
    }
}

/** Preview wrapper simulating a video frame behind overlay components. */
@Composable
internal fun PreviewVideoHost(content: @Composable () -> Unit) {
    SyncStreamTheme {
        Surface(color = Color.Black) {
            Box(Modifier.padding(SyncSpacing.l)) { content() }
        }
    }
}
