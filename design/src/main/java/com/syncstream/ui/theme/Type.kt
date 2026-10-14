package com.syncstream.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

internal val SyncTypography = Typography()

/** Styles outside the Material scale. */
object SyncTextStyles {
    /** Room codes ("4827") and timecodes: monospaced, tabular digits. */
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 4.sp,
        fontFeatureSettings = "tnum",
    )
    val codeLarge = code.copy(fontSize = 40.sp, letterSpacing = 8.sp)
    val timecode = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontFeatureSettings = "tnum")
}
