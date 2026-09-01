// SPDX-License-Identifier: AGPL-3.0-or-later
// Additional permission: see Stores Exception in LICENSE.
// detekt's MagicNumber rule fires on the content paddings below — all are
// tokens/spacing.css's --space-* scale, already named by the val each
// initializes.
@file:Suppress("MagicNumber")

package space.getsub.core.ui.component

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
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import space.getsub.core.ui.R
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private val CONTENT_PADDING = 16.dp
private val CONTENT_GAP = 12.dp

/**
 * A generic camera surface that decodes one QR code and emits its text.
 *
 * This component knows nothing about the meaning of the decoded payload. The
 * caller owns import, navigation, and any profile or routing interpretation.
 * Camera permission is requested only when the surface is first used, and the
 * camera is unbound when the surface leaves composition.
 */
@Composable
fun QrScanner(
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
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    val currentOnResult by rememberUpdatedState(onResult)
    val previewDescription = stringResource(R.string.qr_scan_preview_description)

    DisposableEffect(lifecycleOwner) {
        val cameraExecutor = Executors.newSingleThreadExecutor()
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val delivered = AtomicBoolean(false)
        val disposed = AtomicBoolean(false)
        val providerFuture = ProcessCameraProvider.getInstance(context)

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

    Box(modifier = modifier.clearAndSetSemantics { contentDescription = previewDescription }) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    }
}

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
        TextButton(onClick = onCancel) { Text(stringResource(R.string.qr_scan_cancel_button)) }
    }
}

/** One camera frame's luminance plane in the layout expected by ZXing. */
internal class LuminanceFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val rowStride: Int = width,
)

/** Decodes QR text from CameraX luminance frames. */
internal object QrAnalyzer {
    fun decodeLuminance(frame: LuminanceFrame): String? {
        val source =
            PlanarYUVLuminanceSource(
                frame.data,
                frame.rowStride,
                frame.height,
                0,
                0,
                frame.width,
                frame.height,
                false,
            )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            QRCodeReader().decode(bitmap).text
        } catch (ignored: NotFoundException) {
            null
        } catch (ignored: FormatException) {
            null
        } catch (ignored: ChecksumException) {
            null
        }
    }

    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return decodeLuminance(
            LuminanceFrame(
                data = bytes,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
            ),
        )
    }
}
