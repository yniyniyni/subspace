// SPDX-License-Identifier: AGPL-3.0-or-later
// detekt's MagicNumber rule fires on the content paddings below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes. See core/ui's ConnectControl.kt / feature/profiles' own
// AddServerSheet.kt for the same pattern.
@file:Suppress("MagicNumber")

package art.yniyniyni.subspace.feature.profiles.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import art.yniyniyni.subspace.feature.profiles.R
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val CONTENT_PADDING = 16.dp
private val CONTENT_GAP = 12.dp

/**
 * The camera QR-scan flow, pushed from [art.yniyniyni.subspace.navigation.QrScan].
 *
 * `CAMERA` is requested here, at the point of use, not at app startup — this
 * is the only place in the app that needs it. Denial is handled explicitly
 * rather than left to crash or hang the screen (ARCHITECTURE.md §10.4): a
 * plain denial offers a retry, a permanent denial (the system will not show
 * the prompt again) points at system Settings, and every branch keeps a way
 * back out via [onCancel] rather than stranding the user.
 *
 * [onResult] fires at most once per composition with whatever text
 * [QrAnalyzer] decoded from a camera frame. A scanned `vless://` link *is*
 * config content — a UUID and REALITY key — exactly like a pasted or
 * file-imported one (§5.6), so the caller is expected to feed it into the
 * same `ImportViewModel.import(raw)` path
 * [art.yniyniyni.subspace.feature.profiles.add.AddServerSheet] already uses,
 * not a second import pipeline. This screen never inspects, logs, or stores
 * that string itself — it only forwards what [QrAnalyzer] decoded, and
 * neither ZXing nor CameraX is given it to log on their own account.
 *
 * The camera is released in `onDispose` ([QrCameraPreview]'s own
 * `DisposableEffect`): CameraX does not unbind a use case just because this
 * composable leaves the tree while the host `Activity` stays resumed (e.g. a
 * back navigation that pops this destination without stopping the Activity),
 * so skipping this would leave the camera indicator lit after the user
 * thinks they left.
 */
@Composable
fun QrScanScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var permission by remember { mutableStateOf(context.cameraPermissionState()) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permission =
                when {
                    granted -> CameraPermission.GRANTED
                    // Only true once the user has already refused once
                    // without "don't ask again" / an OEM permanent-denial
                    // path — the system's own signal for "still worth
                    // asking again", not one this code has to track itself.
                    // No `activity` (host isn't an Activity at all) is
                    // treated the same as "will not ask again": there is no
                    // in-app retry path either way, so PERMANENTLY_DENIED's
                    // "open Settings" affordance is the more honest of the
                    // two dead ends.
                    activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true ->
                        CameraPermission.DENIED
                    else -> CameraPermission.PERMANENTLY_DENIED
                }
        }

    LaunchedEffect(Unit) {
        if (permission == CameraPermission.NOT_REQUESTED) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        when (permission) {
            CameraPermission.GRANTED ->
                QrCameraContent(onResult = onResult, onCancel = onCancel)

            CameraPermission.DENIED ->
                QrPermissionMessage(
                    message = stringResource(R.string.qr_scan_permission_denied_message),
                    actionLabel = stringResource(R.string.qr_scan_grant_permission_button),
                    onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onCancel = onCancel,
                )

            CameraPermission.PERMANENTLY_DENIED ->
                QrPermissionMessage(
                    message = stringResource(R.string.qr_scan_permission_permanently_denied_message),
                    actionLabel = stringResource(R.string.qr_scan_open_settings_button),
                    onAction = { context.openAppSettings() },
                    onCancel = onCancel,
                )

            // Waiting on the system permission dialog the LaunchedEffect
            // above just launched; nothing to render yet.
            CameraPermission.NOT_REQUESTED -> Unit
        }
    }
}

private enum class CameraPermission { NOT_REQUESTED, GRANTED, DENIED, PERMANENTLY_DENIED }

private fun Context.cameraPermissionState(): CameraPermission =
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        CameraPermission.GRANTED
    } else {
        CameraPermission.NOT_REQUESTED
    }

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        },
    )
}

@Composable
private fun QrCameraContent(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        QrCameraPreview(onResult = onResult, modifier = Modifier.fillMaxSize())
        // Cancel sits at the top-start corner, inset by systemBars before
        // CONTENT_PADDING — verified on a Pixel 8 (§11): CONTENT_PADDING
        // alone left this inside the system's own gesture-priority region
        // for the status bar / back-gesture edge, which silently swallowed
        // taps meant for this button before they ever reached Compose.
        // `enableEdgeToEdge()` draws content under the system bars, so
        // without the systemBars inset the button was USABLE-LOOKING but
        // not reliably tappable. Same two-step inset FloatingNavigationBar
        // applies at the opposite (navigationBars) edge.
        IconButton(
            onClick = onCancel,
            modifier =
            Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(CONTENT_PADDING),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.qr_scan_cancel_description),
            )
        }
    }
}

/**
 * Binds a [Preview] and an [ImageAnalysis] use case to the current
 * lifecycle. This is the "thin CameraX shell" [QrAnalyzer]'s own KDoc refers
 * to: everything Android/CameraX-specific about turning a live camera feed
 * into a decoded string lives here, in as little code as binding requires,
 * and the actual decode is [QrAnalyzer.decode]/[QrAnalyzer.decodeLuminance] —
 * pure, and covered by `QrAnalyzerTest` off-device.
 */
@Composable
private fun QrCameraPreview(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply {
                // COMPATIBLE (TextureView-backed), not the PERFORMANCE
                // default (SurfaceView-backed): a SurfaceView punches a hole
                // through the window for hardware compositing and, verified
                // on a Pixel 8 (§11), silently swallows touches meant for
                // Compose siblings drawn on top of it in the same Box — the
                // Cancel button underneath never received a tap while this
                // was left on the default. TextureView is a normal View and
                // participates correctly in Compose's own hit-testing.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    val currentOnResult by rememberUpdatedState(onResult)
    val previewDescription = stringResource(R.string.qr_scan_preview_description)

    DisposableEffect(lifecycleOwner) {
        val cameraExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        // Guards against onResult firing more than once for one screen
        // instance (a live feed keeps producing frames after the first hit
        // until the caller navigates away) and against binding a camera
        // that already lost its window by the time the async provider
        // future resolves.
        val delivered = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        val providerFuture = ProcessCameraProvider.getInstance(context)

        // Built here, not inside bindQrCamera, so that function only takes
        // what it needs to bind use cases — this closure is where the
        // decode-once guard and the hop back to mainExecutor for [onResult]
        // actually live.
        val analyzeFrame: (ImageProxy) -> Unit = { image ->
            try {
                if (!delivered.get()) {
                    val decoded = QrAnalyzer.decode(image)
                    if (decoded != null && delivered.compareAndSet(false, true)) {
                        mainExecutor.execute { currentOnResult(decoded) }
                    }
                }
            } finally {
                image.close()
            }
        }

        providerFuture.addListener(
            {
                if (!disposed.get()) {
                    bindQrCamera(
                        cameraProvider = providerFuture.get(),
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        cameraExecutor = cameraExecutor,
                        analyzeFrame = analyzeFrame,
                    )
                }
            },
            mainExecutor,
        )

        onDispose {
            disposed.set(true)
            if (providerFuture.isDone) {
                providerFuture.get().unbindAll()
            }
            cameraExecutor.shutdown()
        }
    }

    // The contentDescription is set on this wrapping Box, not on the
    // AndroidView modifier directly — verified on a Pixel 8 (§11) that a
    // description attached straight to an AndroidView hosting a camera
    // preview surface does not reach the accessibility tree (TextureView's
    // hardware-composited surface appears to bypass it), while the same
    // description on a plain Compose node wrapping it does.
    Box(modifier = modifier.clearAndSetSemantics { contentDescription = previewDescription }) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}

/**
 * The actual CameraX binding, pulled out of [QrCameraPreview] so that
 * composable stays readable: builds [Preview] and [ImageAnalysis] use cases
 * and binds both to [lifecycleOwner]. [analyzeFrame] is built by the caller
 * — it owns the decode-once latch and the hop back to the main thread for
 * `onResult`, so this function only wires CameraX plumbing together.
 */
private fun bindQrCamera(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    cameraExecutor: Executor,
    analyzeFrame: (ImageProxy) -> Unit,
) {
    val preview =
        Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
    val analysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    analysis.setAnalyzer(cameraExecutor, analyzeFrame)
    cameraProvider.unbindAll()
    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
}

@Composable
private fun QrPermissionMessage(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(CONTENT_PADDING),
        verticalArrangement = Arrangement.spacedBy(CONTENT_GAP),
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction) { Text(actionLabel) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.servers_dialog_cancel)) }
    }
}
