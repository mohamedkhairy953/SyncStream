package com.syncstream.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal val SyncDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4F9CF9), onPrimary = Color(0xFF00264D),
    primaryContainer = Color(0xFF123A66), onPrimaryContainer = Color(0xFFD3E4FF),
    secondary = Color(0xFF7FD1AE), onSecondary = Color(0xFF003825),
    secondaryContainer = Color(0xFF1B4D3B), onSecondaryContainer = Color(0xFFB5F0D3),
    background = Color(0xFF0B1221), onBackground = Color(0xFFE6ECF5),
    surface = Color(0xFF0B1221), onSurface = Color(0xFFE6ECF5),
    surfaceVariant = Color(0xFF1B2539), onSurfaceVariant = Color(0xFF8D9AB0),
    surfaceContainerLowest = Color(0xFF070D18), surfaceContainerLow = Color(0xFF0F1726),
    surfaceContainer = Color(0xFF121A2B), surfaceContainerHigh = Color(0xFF1B2539),
    surfaceContainerHighest = Color(0xFF243049),
    outline = Color(0xFF3A4760), outlineVariant = Color(0xFF26314A),
    error = Color(0xFFFF6B6B), onError = Color(0xFF410002),
    errorContainer = Color(0xFF5C1A1A), onErrorContainer = Color(0xFFFFDAD6),
)

internal val SyncLightColorScheme = lightColorScheme(
    primary = Color(0xFF1565C0), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF), onPrimaryContainer = Color(0xFF001C3A),
    secondary = Color(0xFF2E7D63), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB5F0D3), onSecondaryContainer = Color(0xFF002116),
    background = Color(0xFFF7F9FC), onBackground = Color(0xFF101418),
    surface = Color(0xFFF7F9FC), onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE0E5ED), onSurfaceVariant = Color(0xFF4A5568),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFECEFF5), surfaceContainerHigh = Color(0xFFE6EAF1),
    surfaceContainerHighest = Color(0xFFE0E5ED),
    outline = Color(0xFF7A8699), outlineVariant = Color(0xFFC4CCD8),
    error = Color(0xFFB3261E), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

/** Semantic colors Material 3 has no slot for. Video-overlay colors are theme-independent. */
@Immutable
data class SyncExtendedColors(
    val success: Color,
    val warning: Color,
    val scrim: Color = Color.Black.copy(alpha = 0.6f),
    val onScrim: Color = Color.White,
    val onScrimMuted: Color = Color.White.copy(alpha = 0.72f),
    val scrimPill: Color = Color.White.copy(alpha = 0.14f),
)

internal val SyncDarkExtended = SyncExtendedColors(success = Color(0xFF7FD1AE), warning = Color(0xFFF4C06A))
internal val SyncLightExtended = SyncExtendedColors(success = Color(0xFF2E7D63), warning = Color(0xFF8A5A00))

val LocalSyncColors = staticCompositionLocalOf { SyncDarkExtended }
