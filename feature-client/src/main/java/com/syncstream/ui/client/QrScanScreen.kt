package com.syncstream.ui.client

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.syncstream.core.JoinInfo
import com.syncstream.core.JoinLink
import com.syncstream.discovery.DiscoveredService
import com.syncstream.discovery.DiscoveryState
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The client's entry point for joining a master. It leads with the **live list of masters** found
 * on the LAN (mDNS), plus two actions: **Scan QR** (opens the camera on demand — never by default)
 * and **Enter code** (a dialog for the `host:port:pin` the master prints beside its QR). Tapping a
 * discovered master opens the same dialog pre-filled with its `host:port:`, so only the PIN is left.
 *
 * On a cameraless device (e.g. Android TV) the Scan QR action is hidden — the list and the manual
 * dialog cover the whole flow. Any resolved target stashes the PIN in [PendingConnection] and calls
 * [onScanned], guarded so only one path navigates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onScanned: (host: String, port: Int) -> Unit,
    onBack: () -> Unit,
    viewModel: ClientViewModel = viewModel(),
) {
    val context = LocalContext.current
    val hasCameraHardware = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var showScanner by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var manualCode by rememberSaveable { mutableStateOf("") }

    // Camera permission is requested only when the user taps Scan QR — not on entry.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (granted) showScanner = true
    }
    val onScanClick = {
        if (hasCameraPermission) showScanner = true
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Live mDNS list of masters on the LAN.
    val services by viewModel.discovered.collectAsStateWithLifecycle()
    val discoveryState by viewModel.discoveryState.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    // One-shot: whichever path resolves a valid target first wins; the rest are ignored.
    val handled = remember { AtomicBoolean(false) }
    val connect: (JoinInfo) -> Unit = { info ->
        if (handled.compareAndSet(false, true)) {
            PendingConnection.pin = info.pin
            onScanned(info.host, info.port)
        }
    }

    // While the scanner is open, hardware-back closes it rather than leaving the screen.
    BackHandler(enabled = showScanner) { showScanner = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connect to a master") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionButtons(
                showScan = hasCameraHardware,
                onScan = onScanClick,
                onManual = { showManualDialog = true },
            )

            DiscoveredMasters(
                services = services,
                state = discoveryState,
                onPick = { svc ->
                    manualCode = "${svc.host}:${svc.port}:"
                    showManualDialog = true
                },
            )

            Spacer(Modifier.height(8.dp))
        }
    }

    if (showScanner && hasCameraPermission) {
        FullScreenScanner(onResult = connect, onClose = { showScanner = false })
    }

    if (showManualDialog) {
        ManualConnectDialog(
            code = manualCode,
            onCodeChange = { manualCode = it },
            onConnect = { JoinLink.parseCode(manualCode)?.let { connect(it); showManualDialog = false } },
            onDismiss = { showManualDialog = false },
        )
    }
}

@Composable
private fun ActionButtons(showScan: Boolean, onScan: () -> Unit, onManual: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showScan) {
            OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Scan QR")
            }
        }
        OutlinedButton(onClick = onManual, modifier = Modifier.weight(1f)) {
            Icon(Icons.Filled.Keyboard, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Enter code")
        }
    }
}

@Composable
private fun DiscoveredMasters(
    services: List<DiscoveredService>,
    state: DiscoveryState,
    onPick: (DiscoveredService) -> Unit,
) {
    Text("Masters on this network", style = MaterialTheme.typography.titleMedium)
    when {
        services.isNotEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            services.forEach { svc ->
                Card(
                    onClick = { onPick(svc) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            svc.label ?: svc.serviceName,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${svc.host}:${svc.port} — tap, then add the PIN",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        state is DiscoveryState.Failed -> Text(
            "Discovery unavailable (code ${state.errorCode}). Use “Enter code” above.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        state is DiscoveryState.Discovering -> Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        }
        else -> Text(
            "No masters found yet. Use the buttons above to connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
            Column {
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    label = { Text("host:port:pin") },
                    placeholder = { Text("192.168.1.5:8080:1234") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Find this code on the master's QR screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConnect, enabled = JoinLink.parseCode(code) != null) {
                Text("Connect")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FullScreenScanner(onResult: (JoinInfo) -> Unit, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { object { var view: DecoratedBarcodeView? = null } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                DecoratedBarcodeView(ctx).apply {
                    barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    setStatusText("")
                    decodeContinuous(object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult) {
                            JoinLink.parse(result.text ?: return)?.let {
                                pause()
                                onResult(it)
                            }
                        }

                        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) = Unit
                    })
                    holder.view = this
                }
            },
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close scanner", tint = Color.White)
        }

        Text(
            "Point at the master's QR code",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(24.dp),
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> holder.view?.resume()
                Lifecycle.Event.ON_PAUSE -> holder.view?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            holder.view?.pause()
        }
    }
}

/**
 * Process-local hand-off for the PIN decoded/entered on [QrScanScreen] to [ClientScreen]. Kept out
 * of the navigation route so the secret never appears in a back-stack argument or log. Single-shot:
 * [ClientScreen] reads it once on first connect.
 */
internal object PendingConnection {
    @Volatile
    var pin: String = ""
}
