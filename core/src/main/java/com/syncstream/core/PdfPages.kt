package com.syncstream.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer

/**
 * Renders 0-based [page] of [renderer] onto a white bitmap whose longest side is at most
 * [maxDim] px. Must be called under the owner's lock: [PdfRenderer] allows one open page at a
 * time and is not thread-safe. Returns null for an out-of-range page or a rendering failure.
 * Shared by the host (its own view of the PDF) and guests (their downloaded copy).
 */
fun renderPdfPage(renderer: PdfRenderer, page: Int, maxDim: Int = 2048): Bitmap? {
    if (page !in 0 until renderer.pageCount) return null
    return runCatching {
        renderer.openPage(page).use { p ->
            val scale = maxDim.toFloat() / maxOf(p.width, p.height).coerceAtLeast(1)
            val w = (p.width * scale).toInt().coerceAtLeast(1)
            val h = (p.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                Canvas(bmp).drawColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        }
    }.getOrNull()
}
