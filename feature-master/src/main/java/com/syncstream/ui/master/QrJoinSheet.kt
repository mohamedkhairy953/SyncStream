package com.syncstream.ui.master

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.syncstream.core.JoinLink

/**
 * A dialog that shows the master's join QR code (plus the PIN as a manual fallback). The client
 * scans this from [com.syncstream.ui.master] entry points on both the setup screen and the player.
 *
 * [endpoint] is the master's `"ip:port"` (from `MasterViewModel.endpoint`); the QR encodes a
 * [JoinLink]. When the session is not live yet ([endpoint] null / [pin] blank), a placeholder
 * message is shown instead of a code.
 */
@Composable
fun QrJoinSheet(endpoint: String?, pin: String, onDismiss: () -> Unit) {
    val joinLink = remember(endpoint, pin) { buildJoinLink(endpoint, pin) }
    val qr = remember(joinLink) { joinLink?.let { generateQrBitmap(it, QR_SIZE_PX) } }
    val manualCode = remember(endpoint, pin) { buildManualCode(endpoint, pin) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Scan to join") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (qr != null) {
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                    ) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "Join QR code",
                            modifier = Modifier.size(220.dp),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Open the client and point its camera here.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    if (pin.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "PIN $pin",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (manualCode != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "No camera (e.g. a TV)? Type this code on the client:",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            manualCode,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    Text(
                        "Going live… the join code appears once the session is ready.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        },
    )
}

private fun buildJoinLink(endpoint: String?, pin: String): String? {
    if (endpoint.isNullOrBlank() || pin.isBlank()) return null
    val host = endpoint.substringBeforeLast(':', "").takeIf { it.isNotBlank() } ?: return null
    val port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: return null
    return JoinLink.build(host, port, pin)
}

private fun buildManualCode(endpoint: String?, pin: String): String? {
    if (endpoint.isNullOrBlank() || pin.isBlank()) return null
    val host = endpoint.substringBeforeLast(':', "").takeIf { it.isNotBlank() } ?: return null
    val port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: return null
    return JoinLink.buildCode(host, port, pin)
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
    val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val offset = y * width
        for (x in 0 until width) {
            pixels[offset + x] = if (matrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE
        }
    }
    return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

private const val QR_SIZE_PX = 512
