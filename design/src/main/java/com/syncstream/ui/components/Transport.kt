package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.syncstream.ui.theme.SyncShapes
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncTextStyles
import com.syncstream.ui.theme.SyncTheme

private val PlayDiscSize = 56.dp
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Seek slider with elapsed / total timecodes below. Reports the final value on release. */
@Composable
fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier, onScrub: () -> Unit = {}) {
    val colors = SyncTheme.colors
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val max = durationMs.coerceAtLeast(1L).toFloat()
    val shown = if (dragging) dragValue else positionMs.toFloat().coerceIn(0f, max)
    Column(modifier.fillMaxWidth()) {
        Slider(
            value = shown,
            onValueChange = { dragging = true; dragValue = it; onScrub() },
            enabled = durationMs > 0,
            onValueChangeFinished = { dragging = false; onSeek(dragValue.toLong()) },
            valueRange = 0f..max,
            colors = SliderDefaults.colors(
                thumbColor = colors.onScrim,
                activeTrackColor = colors.onScrim,
                inactiveTrackColor = colors.scrimPill,
            ),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = SyncSpacing.s), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatPlaybackTime(shown.toLong()), style = SyncTextStyles.timecode, color = colors.onScrimMuted)
            Text(formatPlaybackTime(durationMs), style = SyncTextStyles.timecode, color = colors.onScrimMuted)
        }
    }
}

/** Replay 10 s, play/pause disc, forward 10 s, then [extra]. */
@Composable
fun TransportControls(
    playing: Boolean,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val colors = SyncTheme.colors
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SyncSpacing.m, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onRewind) {
            Icon(Icons.Filled.Replay10, contentDescription = "Back 10 seconds", tint = colors.onScrim)
        }
        Box(
            Modifier
                .size(PlayDiscSize)
                .clip(CircleShape)
                .background(colors.onScrim)
                .clickable(role = Role.Button, onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = Color.Black,
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = onForward) {
            Icon(Icons.Filled.Forward10, contentDescription = "Forward 10 seconds", tint = colors.onScrim)
        }
        extra()
    }
}

/** Playback-speed pill ("1×") with a dropdown of 0.5, 0.75, 1, 1.25, 1.5, 2. */
@Composable
fun SpeedMenu(speed: Float, onSetSpeed: (Float) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val colors = SyncTheme.colors
    Box(modifier) {
        Box(
            Modifier
                .minimumInteractiveComponentSize()
                .clip(SyncShapes.pill)
                .background(colors.scrimPill)
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = SyncSpacing.m, vertical = SyncSpacing.s),
        ) {
            Text(
                formatSpeed(speed),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (speed != 1f) MaterialTheme.colorScheme.primary else colors.onScrim,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            formatSpeed(option),
                            fontWeight = if (option == speed) FontWeight.Bold else FontWeight.Normal,
                            color = if (option == speed) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    },
                    onClick = { onSetSpeed(option); expanded = false },
                )
            }
        }
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "$speed×"

/** "m:ss" under an hour, "h:mm:ss" otherwise. */
fun formatPlaybackTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun TransportPreviewContent() = PreviewVideoHost {
    Column {
        SeekBar(positionMs = 754_000, durationMs = 3_725_000, onSeek = {})
        TransportControls(playing = false, onPlayPause = {}, onRewind = {}, onForward = {}, extra = { SpeedMenu(1.25f, {}) })
    }
}

@Preview(name = "light") @Composable private fun TransportPreviewLight() = TransportPreviewContent()
@Preview(name = "dark", uiMode = UI_MODE_NIGHT_YES) @Composable private fun TransportPreviewDark() = TransportPreviewContent()
