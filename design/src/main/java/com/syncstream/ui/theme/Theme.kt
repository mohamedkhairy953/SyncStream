package com.syncstream.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/** App-wide theme. Every screen must be inside it; every color/type/shape comes from it. */
@Composable
fun SyncStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSyncColors provides if (darkTheme) SyncDarkExtended else SyncLightExtended) {
        MaterialTheme(
            colorScheme = if (darkTheme) SyncDarkColorScheme else SyncLightColorScheme,
            typography = SyncTypography,
            shapes = SyncShapesMaterial,
            content = content,
        )
    }
}

/** Accessors for the non-Material tokens: `SyncTheme.colors.success`. */
object SyncTheme {
    val colors: SyncExtendedColors
        @Composable @ReadOnlyComposable get() = LocalSyncColors.current
}
