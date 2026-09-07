package com.gios.lightchat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen QR scanner for the agent editor. Reuses the camera-permission pattern
 * from [CameraScreen], but drives a lower-level [ProcessCameraProvider] with an
 * [ImageAnalysis] use case so it can decode frames with ZXing rather than just
 * preview them. Pure-Java ZXing core — no Google Play Services, matching the rest
 * of the app. On the first successful decode it calls [onResult] with the raw text
 * and stops, so exactly one result is ever delivered.
 */
@Composable
fun QrScanScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current

    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    LaunchedEffect(hasCamera) {
        if (hasCamera && !granted && !asked) { asked = true; ask.launch(Manifest.permission.CAMERA) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !hasCamera -> Message("This phone has no camera.")
            !granted -> Message("Camera access is needed to scan the QR code.")
            else -> QrViewfinder(onResult = onResult)
        }
        // Over the preview rather than above it, exactly like CameraScreen's chevron.
        HapticText(
            text = "‹",
            style = ChatType.title,
            color = ChatColors.onSurface,
            textAlign = TextAlign.Start,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 12.dp),
            onClick = onClose,
        )
        Text(
            text = "Point the camera at the QR code",
            style = ChatType.hint,
            color = ChatColors.onSurface,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun QrViewfinder(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    val delivered = remember { AtomicBoolean(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val providerRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val executorRef = remember { mutableStateOf<ExecutorService?>(null) }

    LaunchedEffect(Unit) {
        // Blocking future get, so off the main thread; it's a singleton and resolves fast.
        val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
        providerRef.value = provider
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(1280, 960))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val executor = Executors.newSingleThreadExecutor()
        executorRef.value = executor
        analysis.setAnalyzer(executor) { image ->
            try {
                // One frame at a time on a single thread; KEEP_ONLY_LATEST drops the backlog.
                if (!delivered.get()) {
                    val text = decodeQr(image)
                    if (text != null && delivered.compareAndSet(false, true)) {
                        handler.post { onResult(text) }
                    }
                }
            } finally {
                image.close()
            }
        }
        runCatching { provider.unbindAll() }
        runCatching {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { providerRef.value?.unbindAll() }
            executorRef.value?.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/** Decodes a QR code from [image]'s luma plane, or null when no code is in frame. */
private fun decodeQr(image: ImageProxy): String? = try {
    val source = PlanarYUVLuminanceSource(
        luma(image),
        image.width,
        image.height,
        0, 0, image.width, image.height,
        false,
    )
    val hints = HashMap<DecodeHintType, Any>()
    hints[DecodeHintType.POSSIBLE_FORMATS] = listOf(BarcodeFormat.QR_CODE)
    // A code carrying a full API key skews dense (small modules for the code's physical
    // size), and a phone off a screen already fights moiré/glare that print doesn't have —
    // TRY_HARDER trades a bit of per-frame CPU for meaningfully better odds on exactly
    // that case. Worth it here: this only runs while the scanner is open, not continuously.
    hints[DecodeHintType.TRY_HARDER] = true
    MultiFormatReader()
        .apply { setHints(hints) }
        .decode(BinaryBitmap(HybridBinarizer(source)))
        .text
} catch (t: Exception) {
    null
}

/**
 * Copies [image]'s Y plane into a tightly-packed byte array. Handles the row padding
 * and pixel stride that the contiguous fast path can't, so a sensor that pads rows
 * still decodes rather than throwing an out-of-bounds read. The duplicated buffer
 * keeps the shared plane's position untouched for the next consumer.
 */
private fun luma(image: ImageProxy): ByteArray {
    val plane = image.planes[0]
    val buffer = plane.buffer.duplicate()
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val width = image.width
    val height = image.height
    val data = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width) {
        buffer.rewind()
        buffer.get(data)
        return data
    }
    var out = 0
    for (y in 0 until height) {
        val rowStart = y * rowStride
        if (pixelStride == 1) {
            for (x in 0 until width) data[out++] = buffer.get(rowStart + x)
        } else {
            for (x in 0 until width) data[out++] = buffer.get(rowStart + x * pixelStride)
        }
    }
    return data
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = ChatType.body,
        color = ChatColors.onSurfaceDisabled,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}
