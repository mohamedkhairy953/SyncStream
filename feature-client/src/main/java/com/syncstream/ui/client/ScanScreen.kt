package com.syncstream.ui.client

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.syncstream.core.JoinInfo
import com.syncstream.core.JoinLink
import com.syncstream.ui.components.EmptyState
import com.syncstream.ui.components.SecondaryButton
import com.syncstream.ui.theme.SyncSpacing
import com.syncstream.ui.theme.SyncTheme
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Guest · Scan. Opens straight onto the full-screen QR scanner. The camera permission is requested
 * on entry if not yet granted; a denial (or the close button) calls [onBack]. A valid join QR
 * joins that room directly with its PIN and calls [onJoined] (one-shot). On a cameraless device
 * (e.g. Android TV) it explains that and offers Back; Home normally hides Scan QR there anyway.
 */
@Composable
fun ScanScreen(viewModel: ClientViewModel, onJoined: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val hasCameraHardware = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    if (!hasCameraHardware) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyState(
                Icons.Filled.NoPhotography,
                "No camera on this device",
                "Use Enter code to join a room instead.",
                action = { SecondaryButton("Back", onClick = onBack) },
            )
        }
        return
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (!granted) onBack()
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // One-shot: the scanner can fire repeatedly before it pauses; only the first valid QR joins.
    val handled = remember { AtomicBoolean(false) }
    val join: (JoinInfo) -> Unit = { info ->
        if (handled.compareAndSet(false, true)) {
            viewModel.joinDirect(RoomTarget(info.host, info.port, info.host), info.pin)
            onJoined()
        }
    }

    if (hasCameraPermission) {
        FullScreenScanner(onResult = join, onClose = onBack)
    } else {
        // Waiting on the permission dialog: keep the camera-black backdrop rather than flashing UI.
        Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

@Composable
private fun FullScreenScanner(onResult: (JoinInfo) -> Unit, onClose: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val holder = remember { object { var view: DecoratedBarcodeView? = null } }
    val colors = SyncTheme.colors

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
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(SyncSpacing.s),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Close scanner", tint = colors.onScrim)
        }

        Text(
            "Point at the QR code on the host's screen",
            color = colors.onScrim,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colors.scrim)
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(SyncSpacing.xl),
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
