package com.syncstream.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Presentation kinds a room can show (the `mediaKind` string carried by the sync heartbeat):
 * "video", "audio", "image", "pdf", "file". Shared icon/label lookups so the host picker, the
 * host player and the guest viewer agree.
 */
object MediaKinds {
    const val VIDEO = "video"
    const val AUDIO = "audio"
    const val IMAGE = "image"
    const val PDF = "pdf"
    const val FILE = "file"

    /** Kinds that have a timeline (play/pause/seek/speed); everything else is discrete. */
    fun isTimed(kind: String): Boolean = kind == VIDEO || kind == AUDIO

    fun icon(kind: String): ImageVector = when (kind) {
        AUDIO -> Icons.Filled.Audiotrack
        IMAGE -> Icons.Filled.Image
        PDF -> Icons.Filled.PictureAsPdf
        FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
        else -> Icons.Filled.Movie
    }

    /** Short noun for the kind ("video", "song", "photo", "PDF", "file"). */
    fun noun(kind: String): String = when (kind) {
        AUDIO -> "song"
        IMAGE -> "photo"
        PDF -> "PDF"
        FILE -> "file"
        else -> "video"
    }

    /** MIME filter handed to the system document picker for the kind. */
    fun mimeTypes(kind: String): Array<String> = when (kind) {
        AUDIO -> arrayOf("audio/*")
        IMAGE -> arrayOf("image/*")
        PDF -> arrayOf("application/pdf")
        FILE -> arrayOf("*/*")
        else -> arrayOf("video/*")
    }
}
