package com.syncstream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4F9CF9),
    onPrimary = Color(0xFF00264D),
    secondary = Color(0xFF7FD1AE),
    background = Color(0xFF0B1221),
    surface = Color(0xFF121A2B),
    onBackground = Color(0xFFE6ECF5),
    onSurface = Color(0xFFE6ECF5),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF2E7D63),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF101418),
    onSurface = Color(0xFF101418),
    error = Color(0xFFB3261E),
)

/**
 * App-wide Material3 theme. Defaults to following the system dark/light setting.
 * Phase-2 screens should wrap their content in [SyncStreamTheme].
 */
@Composable
fun SyncStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
