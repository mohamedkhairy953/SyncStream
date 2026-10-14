package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.syncstream.ui.theme.SyncMotion
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncTextStyles

private val SlotWidth = 44.dp
private val SlotHeight = 56.dp
private val KeyHeight = 56.dp
private val ShakeAmplitude = 10.dp
private val FocusBorder = 2.dp
private val IdleBorder = 1.dp

/** Room-code digit slots. The slot at index == code.length shows the focus border; [error] triggers a shake. */
@Composable
fun CodeSlots(code: String, modifier: Modifier = Modifier, length: Int = 4, error: Boolean = false) {
    val shake = remember { Animatable(0f) }
    val amplitudePx = with(LocalDensity.current) { ShakeAmplitude.toPx() }
    LaunchedEffect(error) {
        if (error) {
            shake.snapTo(0f)
            shake.animateTo(
                0f,
                keyframes {
                    durationMillis = SyncMotion.SHAKE_MS
                    -amplitudePx at SyncMotion.SHAKE_MS / 8
                    amplitudePx at SyncMotion.SHAKE_MS * 3 / 8
                    -amplitudePx / 2 at SyncMotion.SHAKE_MS * 5 / 8
                    amplitudePx / 2 at SyncMotion.SHAKE_MS * 7 / 8
                    0f at SyncMotion.SHAKE_MS
                },
            )
        }
    }
    Row(
        modifier.graphicsLayer { translationX = shake.value },
        horizontalArrangement = Arrangement.spacedBy(SyncSpacing.s),
    ) {
        for (i in 0 until length) {
            val focused = i == code.length
            val borderColor = when {
                error -> MaterialTheme.colorScheme.error
                focused -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Surface(
                modifier = Modifier.size(SlotWidth, SlotHeight),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(if (focused || error) FocusBorder else IdleBorder, borderColor),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        code.getOrNull(i)?.toString() ?: "",
                        style = SyncTextStyles.code.copy(letterSpacing = TextUnit.Unspecified),
                        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 3x4 keypad: 1-9, blank, 0, backspace. Keys are focusable for D-pad navigation. */
@Composable
fun NumericKeypad(onDigit: (Char) -> Unit, onBackspace: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
        val rows = listOf("123", "456", "789")
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
                row.forEach { d -> Key(Modifier.weight(1f), onClick = { onDigit(d) }) { KeyLabel(d.toString()) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
            Box(Modifier.weight(1f).height(KeyHeight))
            Key(Modifier.weight(1f), onClick = { onDigit('0') }) { KeyLabel("0") }
            Key(Modifier.weight(1f), onClick = onBackspace) {
                Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete")
            }
        }
    }
}

@Composable
private fun KeyLabel(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall)

@Composable
private fun Key(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(KeyHeight),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun CodeEntryPreviewContent() = PreviewHost {
    Column(verticalArrangement = Arrangement.spacedBy(SyncSpacing.l), horizontalAlignment = Alignment.CenterHorizontally) {
        CodeSlots("48")
        CodeSlots("4827", error = true)
        NumericKeypad({}, {})
    }
}

@Preview(name = "light") @Composable private fun CodeEntryPreviewLight() = CodeEntryPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun CodeEntryPreviewDark() = CodeEntryPreviewContent()
