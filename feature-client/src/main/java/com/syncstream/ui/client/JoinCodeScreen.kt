package com.syncstream.ui.client

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syncstream.core.JoinLink
import com.syncstream.ui.components.CodeSlots
import com.syncstream.ui.components.NumericKeypad
import com.syncstream.ui.components.PrimaryButton
import com.syncstream.ui.components.SecondaryButton
import com.syncstream.ui.theme.SyncSpacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val CODE_LENGTH = 4
private const val DISCOVERY_WAIT_MS = 2_000L
private val KeypadMaxWidth = 360.dp
private val StatusLineMinHeight = 24.dp
private val StatusSpinnerSize = 16.dp
private val StatusSpinnerStroke = 2.dp

/**
 * Guest · Join code. Four digit slots plus an on-screen keypad (a hardware keyboard or D-pad also
 * types). With [room] chosen on Home the code is tried against that room only; with `room == null`
 * it is tried against every room discovered on the LAN, first acceptor wins. "Trouble connecting?"
 * opens the manual `host:port:pin` dialog as a fallback.
 */
@Composable
fun JoinCodeScreen(
    viewModel: ClientViewModel,
    room: RoomTarget?,
    onJoined: () -> Unit,
    onBack: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var showError by rememberSaveable { mutableStateOf(false) }
    var showManual by rememberSaveable { mutableStateOf(false) }
    var manualCode by rememberSaveable { mutableStateOf(room?.let { "${it.host}:${it.port}:" } ?: "") }
    var searching by remember { mutableStateOf(false) }
    val joinStatus by viewModel.joinStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // "No room chosen" needs the discovered list; browse only while this screen shows.
    if (room == null) {
        DisposableEffect(viewModel) {
            viewModel.startDiscovery()
            onDispose { viewModel.stopDiscovery() }
        }
    }

    val busy = searching || joinStatus is JoinStatus.Trying
    val result = (joinStatus as? JoinStatus.Done)?.result

    LaunchedEffect(joinStatus) {
        when ((joinStatus as? JoinStatus.Done)?.result) {
            is MatchResult.Joined -> {
                viewModel.resetJoinStatus()
                onJoined()
            }
            MatchResult.WrongCode -> {
                showError = true
                code = ""
            }
            else -> Unit
        }
    }

    fun submit(full: String) {
        if (room != null) {
            viewModel.joinWithCode(full, listOf(room))
            return
        }
        scope.launch {
            searching = true
            val found = withTimeoutOrNull(DISCOVERY_WAIT_MS) { viewModel.discovered.first { it.isNotEmpty() } }
                ?: viewModel.discovered.value
            searching = false
            viewModel.joinWithCode(full, found.map { it.toRoomTarget() })
        }
    }

    // Typing again clears the previous error; flips the slots' error flag back to false so the
    // next wrong code shakes them again.
    fun clearOutcome() {
        if (showError) showError = false
        if (joinStatus is JoinStatus.Done) viewModel.resetJoinStatus()
    }

    fun onDigit(d: Char) {
        if (busy) return
        clearOutcome()
        if (code.length < CODE_LENGTH) {
            code += d
            if (code.length == CODE_LENGTH) submit(code)
        }
    }

    fun onBackspace() {
        if (busy) return
        clearOutcome()
        code = code.dropLast(1)
    }

    // Backing out abandons any code match still running so it can't connect behind our back.
    val leave: () -> Unit = {
        viewModel.cancelJoin()
        onBack()
    }
    BackHandler(onBack = leave)

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val ch = event.utf16CodePoint.toChar()
                when {
                    ch in '0'..'9' -> { onDigit(ch); true }
                    event.key == Key.Backspace || event.key == Key.Delete -> { onBackspace(); true }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable()
            .safeDrawingPadding(),
    ) {
        IconButton(onClick = leave, modifier = Modifier.padding(SyncSpacing.xs)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SyncSpacing.xl, vertical = SyncSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(SyncSpacing.l),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(SyncSpacing.xs)) {
                Text(
                    room?.let { "Join ${it.label}" } ?: "Enter room code",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Enter the code on the host's screen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            CodeSlots(code, length = CODE_LENGTH, error = showError)

            StatusLine(busy = busy, result = result)

            NumericKeypad(
                onDigit = ::onDigit,
                onBackspace = ::onBackspace,
                modifier = Modifier.widthIn(max = KeypadMaxWidth).fillMaxWidth(),
            )

            val noRoom = result == MatchResult.NoMatch || result == MatchResult.NoRooms ||
                result is MatchResult.Unreachable
            if (noRoom) {
                SecondaryButton("Trouble connecting?", onClick = { showManual = true }, enabled = !busy)
            } else {
                TextButton(onClick = { showManual = true }, enabled = !busy) { Text("Trouble connecting?") }
            }
        }
    }

    if (showManual) {
        ManualConnectDialog(
            code = manualCode,
            onCodeChange = { manualCode = it },
            onConnect = {
                JoinLink.parseCode(manualCode)?.let { info ->
                    showManual = false
                    viewModel.joinDirect(RoomTarget(info.host, info.port, info.host), info.pin)
                    onJoined()
                }
            },
            onDismiss = { showManual = false },
        )
    }
}

@Composable
private fun StatusLine(busy: Boolean, result: MatchResult?) {
    Box(Modifier.heightIn(min = StatusLineMinHeight), contentAlignment = Alignment.Center) {
        val error = MaterialTheme.colorScheme.error
        when {
            busy -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
                CircularProgressIndicator(Modifier.size(StatusSpinnerSize), strokeWidth = StatusSpinnerStroke)
                Text("Connecting…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            result == MatchResult.WrongCode ->
                Text("Wrong code, try again", style = MaterialTheme.typography.bodyMedium, color = error)
            result is MatchResult.RoomFull ->
                Text("That room is full (4 guests)", style = MaterialTheme.typography.bodyMedium, color = error)
            result is MatchResult.Unreachable ->
                Text(
                    "Couldn't reach ${result.room.label}. Check both devices are on the same Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = error,
                    textAlign = TextAlign.Center,
                )
            result == MatchResult.NoMatch || result == MatchResult.NoRooms ->
                Text("No room with that code nearby", style = MaterialTheme.typography.bodyMedium, color = error)
        }
    }
}

/** Raw `host:port:pin` fallback, for networks where discovery or QR scanning doesn't work. */
@Composable
private fun ManualConnectDialog(
    code: String,
    onCodeChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SyncSpacing.s)) {
                Text(
                    "Type the address shown under the QR on the host's screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text("host:port:code") },
                    placeholder = { Text("192.168.1.5:8080:1234") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            PrimaryButton("Connect", onClick = onConnect, enabled = JoinLink.parseCode(code) != null)
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
