package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.syncstream.ui.theme.SyncShapes
import com.syncstream.ui.theme.SyncSpacing

private val ButtonHeight = 40.dp
private val LargeButtonHeight = 56.dp
private val ButtonIconSize = 18.dp

@Composable
private fun buttonShape(large: Boolean): Shape = if (large) MaterialTheme.shapes.large else SyncShapes.pill

@Composable
private fun RowScope.ButtonContent(text: String, large: Boolean, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonIconSize))
        Spacer(Modifier.width(SyncSpacing.s))
    }
    Text(text, style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
}

/** Filled button; [large] = 56 dp tall with large corner radius, otherwise a 40 dp pill. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(if (large) LargeButtonHeight else ButtonHeight),
        enabled = enabled,
        shape = buttonShape(large),
    ) { ButtonContent(text, large, icon) }
}

/** Outlined button; same sizing as [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    large: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(if (large) LargeButtonHeight else ButtonHeight),
        enabled = enabled,
        shape = buttonShape(large),
    ) { ButtonContent(text, large, icon) }
}

@Composable
private fun ButtonsPreviewContent() {
    PreviewHost {
        Column(verticalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
            PrimaryButton("Host a room", {}, large = true, icon = Icons.Filled.Cast)
            SecondaryButton("Enter code", {}, large = true)
            PrimaryButton("Join", {})
            SecondaryButton("Browse", {}, icon = Icons.Filled.Cast)
            PrimaryButton("Disabled", {}, enabled = false)
        }
    }
}

@Preview(name = "light") @Composable private fun ButtonsPreviewLight() = ButtonsPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun ButtonsPreviewDark() = ButtonsPreviewContent()
