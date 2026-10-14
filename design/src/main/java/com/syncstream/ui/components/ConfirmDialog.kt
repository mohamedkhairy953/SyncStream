package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/** Confirm/cancel dialog; [destructive] colors the confirm action with the error color. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    dismissText: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissText) } },
    )
}

@Composable
private fun ConfirmDialogPreviewContent() = PreviewHost {
    ConfirmDialog("End room?", "Guests will be disconnected.", "End room", {}, {}, destructive = true)
}

@Preview(name = "light") @Composable private fun ConfirmDialogPreviewLight() = ConfirmDialogPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun ConfirmDialogPreviewDark() = ConfirmDialogPreviewContent()
