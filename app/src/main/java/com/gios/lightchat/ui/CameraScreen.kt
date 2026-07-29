package com.gios.lightchat.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The in-app viewfinder, opened from the photo picker.
 *
 * Inline rather than an `ACTION_IMAGE_CAPTURE` handoff so you never leave LightChat,
 * and so the shot is framed with LightOS's grayscale already lifted — the picker holds
 * [com.gios.lightchat.ColorMode] for as long as it and this are up, which is the only
 * way to see colour through this phone's viewfinder.
 *
 * Capture grabs the frame already on screen (`PreviewView.bitmap`) rather than running
 * an `ImageCapture` round-trip — LightTip's trick, worked out on this hardware, and
 * instant. The consequence worth knowing is that the photo is the size and crop of the
 * viewfinder, not of the sensor.
 */
@Composable
fun CameraScreen(onCaptured: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Asked before anything binds: uses-feature is required="false", so a build with no
    // camera can install this app, and CameraX's failure there is a silent one (the bind
    // happens inside a future nobody is watching, so a bare bindToLifecycle looks like
    // it succeeded and you get a black rectangle).
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
            !granted -> Message("Camera access is needed to take a photo.")
            else -> Viewfinder(lifecycleOwner = lifecycleOwner, onCaptured = onCaptured)
        }
        // Over the preview rather than above it: the viewfinder fills the screen, and a
        // chevron on top of the image is how every camera does this.
        HapticText(
            text = "‹",
            style = ChatType.title,
            color = ChatColors.onSurface,
            textAlign = TextAlign.Start,
            modifier = Modifier.align(Alignment.TopStart).padding(start = 20.dp, top = 12.dp),
            onClick = onClose,
        )
    }
}

@Composable
private fun Viewfinder(lifecycleOwner: LifecycleOwner, onCaptured: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // COMPATIBLE, not the default PERFORMANCE: PERFORMANCE uses a SurfaceView, which
    // shows through by punching a transparent hole in the window — fragile inside the
    // opaque Surface this screen is drawn in — and it's also the mode whose getBitmap()
    // blocks the caller on a PixelCopy.
    val controller = remember { LifecycleCameraController(context) }
    var preview by remember { mutableStateOf<PreviewView?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        // Unbound explicitly: the controller holds the camera open, and leaving that to
        // the lifecycle keeps it open until the activity itself stops.
        onDispose { runCatching { controller.unbind() } }
    }

    // The shutter is gated on this, not on a non-null bitmap. PreviewView hands back a
    // bitmap as soon as its surface is valid — before any camera frame has arrived — and
    // an all-zero bitmap compresses to a perfectly valid black JPEG, which would then be
    // sent to somebody.
    val view = preview
    DisposableEffect(lifecycleOwner, view) {
        val state = view?.previewStreamState
        val observer = Observer<PreviewView.StreamState> {
            streaming = it == PreviewView.StreamState.STREAMING
        }
        state?.observe(lifecycleOwner, observer)
        onDispose { state?.removeObserver(observer) }
    }

    // Every way a bind can fail is asynchronous and swallowed by CameraX, so rather than
    // trying to catch each one, treat "no frames after this long" as the failure.
    LaunchedEffect(streaming) {
        if (!streaming) {
            delay(BIND_TIMEOUT_MS)
            if (!streaming) failure = "Camera unavailable"
        } else {
            failure = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    this.controller = controller
                    preview = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            val problem = failure
            when {
                problem != null ->
                    Text(text = problem, style = ChatType.body, color = ChatColors.onSurfaceDim)
                else -> HapticText(
                    text = if (capturing) "…" else "Capture",
                    style = ChatType.title,
                    // Dim until there are frames to capture, so the control tells you
                    // whether pressing it will do anything.
                    color = if (streaming && !capturing) ChatColors.onSurface else ChatColors.onSurfaceDisabled,
                    onClick = {
                        val target = preview
                        if (streaming && !capturing && target != null) {
                            capturing = true
                            scope.launch {
                                // bitmap on the main thread (it reads the view), then the
                                // compress, the file write and the MediaStore copy off it
                                // — together those are hundreds of milliseconds, and doing
                                // them in the click handler froze the UI through the whole
                                // capture, so the "…" never even rendered.
                                val bitmap = target.bitmap
                                val file = if (bitmap == null) null else {
                                    withContext(Dispatchers.IO) { save(context, bitmap) }
                                }
                                if (file != null) {
                                    onCaptured(file)
                                } else {
                                    // Unlatched: without this a failed save leaves the
                                    // shutter stuck on "…" with no way to retry.
                                    capturing = false
                                    failure = "Couldn’t save that photo"
                                }
                            }
                        }
                    },
                )
            }
        }
    }
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

/**
 * Writes the frame to the cache (so it can be sent) and, best-effort, into
 * `DCIM/LightChat` through MediaStore so the photo is kept and turns up in the picker
 * next time. MediaStore for the copy because direct writes into shared storage aren't
 * permitted on 29+ — reading by path is, which is what [com.gios.lightchat.Gallery]
 * relies on, but writing isn't. Call off-main.
 */
private fun save(context: Context, bitmap: Bitmap): File? {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "cam-" + System.currentTimeMillis() + ".jpg")
    val ok = runCatching {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }.getOrDefault(false)
    if (!ok || file.length() == 0L) return null
    keepInDcim(context, file)
    return file
}

private fun keepInDcim(context: Context, source: File) {
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/LightChat")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return
        context.contentResolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
    }
}

/** No frames within this long after binding means the camera isn't coming up. */
private const val BIND_TIMEOUT_MS = 4_000L
