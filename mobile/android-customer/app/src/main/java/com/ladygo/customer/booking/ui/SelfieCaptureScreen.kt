package com.ladygo.customer.booking.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ladygo.customer.R
import com.ladygo.customer.booking.SelfieEncoder

/**
 * Front-camera live selfie, required once per booking (docs/api/driver-onboarding.md §4). The photo is
 * held in memory only until it is uploaded - never written to storage or the gallery (in-memory capture, no temp file). The
 * camera-permission request is made here, in context, not at app start.
 */
@Composable
fun SelfieCaptureScreen(
    isUploading: Boolean,
    error: String?,
    onCaptured: (ByteArray) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA) }

    SecureWindowEffect(enabled = true)
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.selfie_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.selfie_hint))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (granted) {
            CameraCapture(isUploading = isUploading, onCaptured = onCaptured)
        } else {
            Text(stringResource(R.string.selfie_permission_needed))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.selfie_grant_permission_action))
            }
            // After a permanent denial the system answers a request instantly with no dialog.
            OutlinedButton(onClick = { openAppSettings(context) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.selfie_open_settings_action))
            }
        }
        OutlinedButton(onClick = onCancel, enabled = !isUploading, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.selfie_cancel_action))
        }
    }
}

@Composable
private fun CameraCapture(
    isUploading: Boolean,
    onCaptured: (ByteArray) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture =
        remember {
            ImageCapture
                .Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector
                        .Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(CAPTURE_WIDTH, CAPTURE_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        ).build(),
                ).build()
        }
    var captureError by remember { mutableStateOf<String?>(null) }
    val captureFailedMessage = stringResource(R.string.selfie_capture_failed)

    // The camera must stop streaming the moment this step is left (cancel, booking in flight, error...),
    // not when the whole booking screen finally leaves the back stack.
    DisposableEffect(lifecycleOwner) {
        onDispose { ProcessCameraProvider.getInstance(context).get().unbindAll() }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = Modifier.fillMaxWidth().height(CAMERA_PREVIEW_HEIGHT),
    )
    captureError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(
        onClick = {
            captureError = null
            // In-memory capture: the JPEG goes from the camera straight to the uploader and is never written
            // to a file (OutputFileOptions would stage it in a temp file first).
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val raw = ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) }
                        val rotation = image.imageInfo.rotationDegrees
                        image.close()
                        val encoded = SelfieEncoder.encode(raw, rotation)
                        if (encoded == null) captureError = captureFailedMessage else onCaptured(encoded)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        captureError = captureFailedMessage
                    }
                },
            )
        },
        enabled = !isUploading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (isUploading) R.string.selfie_uploading else R.string.selfie_capture_action))
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private val CAMERA_PREVIEW_HEIGHT = 360.dp
private const val CAPTURE_WIDTH = 1280
private const val CAPTURE_HEIGHT = 960
