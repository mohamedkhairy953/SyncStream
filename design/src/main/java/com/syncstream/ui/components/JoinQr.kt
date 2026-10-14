package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.syncstream.ui.theme.SyncSpacing

private const val QR_SIZE_PX = 512

/**
 * Renders [content] as a QR code on a white tile. A QR code must stay black-on-white in both
 * themes, so this file is the one place allowed to use literal colors.
 */
@Composable
fun JoinQr(content: String, modifier: Modifier = Modifier, size: Dp = 160.dp) {
    val bitmap = remember(content) { generateQrBitmap(content, QR_SIZE_PX).asImageBitmap() }
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White)
            .padding(SyncSpacing.s),
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "QR code to join the room",
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(size - SyncSpacing.l),
        )
    }
}

private fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
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

@Composable
private fun JoinQrPreviewContent() = PreviewHost { JoinQr("syncstream://join?host=10.0.0.2&port=8080&pin=4827") }

@Preview(name = "light") @Composable private fun JoinQrPreviewLight() = JoinQrPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun JoinQrPreviewDark() = JoinQrPreviewContent()
