package com.syncstream.ui.client

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.syncstream.discovery.DiscoveredService
import com.syncstream.discovery.DiscoveryState

/**
 * Lists masters discovered via mDNS and offers a debug manual host:port entry as a fallback
 * (emulators cannot do mDNS). Selecting a master opens a PIN dialog; on confirm the PIN is stashed
 * for [ClientScreen] and navigation proceeds via [onConnect].
 *
 * The PIN is passed forward through a process-local holder ([PendingConnection]) rather than the
 * navigation route, so it never lands in a back-stack argument or log.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onConnect: (host: String, port: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: ClientViewModel = viewModel(),
) {
    val services by viewModel.discovered.collectAsStateWithLifecycle()
    val state by viewModel.discoveryState.collectAsStateWithLifecycle()

    DisposableEffect(viewModel) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    // The (host, port) awaiting a PIN before we navigate.
    var pendingTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find a master") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.stopDiscovery()
                        viewModel.startDiscovery()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            DiscoveryStatusRow(state = state, count = services.size)

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(services, key = { it.serviceName }) { svc ->
                    MasterCard(service = svc, onClick = { pendingTarget = svc.host to svc.port })
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            ManualConnectSection(onConnect = { host, port -> pendingTarget = host to port })

            Spacer(Modifier.height(16.dp))
        }
    }

    pendingTarget?.let { (host, port) ->
        PinEntryDialog(
            host = host,
            port = port,
            onDismiss = { pendingTarget = null },
            onConfirm = { pin ->
                PendingConnection.pin = pin
                pendingTarget = null
                onConnect(host, port)
            },
        )
    }
}

@Composable
private fun DiscoveryStatusRow(state: DiscoveryState, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state) {
            DiscoveryState.Discovering -> {
                CircularProgressIndicator(modifier = Modifier.height(18.dp).padding(end = 8.dp), strokeWidth = 2.dp)
                Text("Scanning the local network… ($count found)", style = MaterialTheme.typography.bodyMedium)
            }
            is DiscoveryState.Failed -> Text(
                "Discovery failed (code ${state.errorCode}). Check router AP isolation, then use manual entry below.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            DiscoveryState.Idle -> Text(
                if (count == 0) "No masters yet — pull Rescan or enter a host manually."
                else "$count master(s) found.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MasterCard(service: DiscoveredService, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                service.label ?: service.serviceName,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${service.host}:${service.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManualConnectSection(onConnect: (host: String, port: Int) -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }

    Text("Manual connect (debug)", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = host,
            onValueChange = { host = it.trim() },
            label = { Text("Host / IP") },
            singleLine = true,
            modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onConnect(host, port.toIntOrNull() ?: 0) },
        enabled = host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Connect")
    }
}

@Composable
private fun PinEntryDialog(
    host: String,
    port: Int,
    onDismiss: () -> Unit,
    onConfirm: (pin: String) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN") },
        text = {
            Column {
                Text(
                    "Enter the 4-digit PIN shown on $host:$port.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                    label = { Text("PIN") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin) },
                enabled = pin.length == 4,
            ) { Text("Connect") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Process-local hand-off for the PIN entered on [DiscoveryScreen] to [ClientScreen]. Kept out of
 * the navigation route so the secret never appears in a back-stack argument. Single-shot:
 * [ClientScreen] reads it once on first connect.
 */
internal object PendingConnection {
    @Volatile
    var pin: String = ""
}
