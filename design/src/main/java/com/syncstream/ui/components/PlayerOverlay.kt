package com.syncstream.ui.components

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.syncstream.ui.theme.SyncMotion
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncTheme
import kotlinx.coroutines.delay

/**
 * Full-screen player chrome. [video] fills the screen; [topBar] and [bottomBar] fade with [visible]
 * on scrim gradients; [centerContent] is always shown (status messages). A tap anywhere on the
 * video area calls [onTap].
 */
@Composable
fun PlayerOverlayScaffold(
    visible: Boolean,
    onTap: () -> Unit,
    video: @Composable () -> Unit,
    topBar: @Composable RowScope.() -> Unit,
    bottomBar: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    centerContent: @Composable BoxScope.() -> Unit = {},
) {
    val scrim = SyncTheme.colors.scrim
    val fade = tween<Float>(SyncMotion.OVERLAY_FADE_MS)
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) { video() }
        Box(
            Modifier
                .fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onTap),
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { centerContent() }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(fade),
            exit = fadeOut(fade),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(scrim, Color.Transparent)))
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(horizontal = SyncSpacing.s, vertical = SyncSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
                content = topBar,
            )
        }
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(fade),
            exit = fadeOut(fade),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, scrim)))
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(horizontal = SyncSpacing.l, vertical = SyncSpacing.s),
                content = bottomBar,
            )
        }
    }
}

/** Holds overlay visibility. Auto-hides while playing; [poke] restarts the timer. */
@Stable
class OverlayVisibility internal constructor() {
    var visible: Boolean by mutableStateOf(true)
        private set
    internal var pokes: Int by mutableIntStateOf(0)
        private set

    fun show() { visible = true }
    fun hide() { visible = false }

    /** Show (if hidden) and restart the auto-hide timer; call on any user interaction with the controls. */
    fun poke() {
        visible = true
        pokes++
    }
}

@Composable
fun rememberOverlayVisibility(playing: Boolean): OverlayVisibility {
    val state = remember { OverlayVisibility() }
    LaunchedEffect(playing, state.visible, state.pokes) {
        if (playing && state.visible) {
            delay(SyncMotion.OVERLAY_AUTO_HIDE_MS)
            state.hide()
        }
    }
    return state
}

@Composable
private fun PlayerOverlayPreviewContent() = PreviewVideoHost {
    PlayerOverlayScaffold(
        visible = true,
        onTap = {},
        video = {},
        topBar = { RoomChip("4827", {}) },
        bottomBar = { Text("Bottom bar", color = SyncTheme.colors.onScrim) },
        centerContent = { Text("Waiting for the host", color = SyncTheme.colors.onScrim) },
    )
}

@Preview(name = "light", heightDp = 240) @Composable private fun PlayerOverlayPreviewLight() = PlayerOverlayPreviewContent()
@Preview(name = "dark", heightDp = 240, uiMode = UI_MODE_NIGHT_YES) @Composable private fun PlayerOverlayPreviewDark() = PlayerOverlayPreviewContent()
