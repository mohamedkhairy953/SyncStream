package com.syncstream.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val SyncShapesMaterial = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),    // thumbnails, digit slots, keypad keys
    medium = RoundedCornerShape(12.dp),  // cards
    large = RoundedCornerShape(16.dp),   // sheets, large buttons
    extraLarge = RoundedCornerShape(28.dp),
)

object SyncShapes {
    val pill = RoundedCornerShape(percent = 50)
}
