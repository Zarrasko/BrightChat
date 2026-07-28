package com.craigeley.chat.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.craigeley.chat.ColorMode
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType

/**
 * In-app FaceTime: a full-screen WebView on a `facetime.apple.com` call link —
 * web FaceTime, which is plain WebRTC, and the system WebView supports WebRTC.
 * The phone has no browser, so this *is* how a FaceTime link opens here; it's
 * used both for links we mint ourselves (the thread header's Call control →
 * `ChatViewModel.startFaceTime`) and for facetime.apple.com links tapped inside
 * a message body.
 *
 * Drawn as an opaque overlay on top of the thread (the image-viewer pattern —
 * the LazyColumn never leaves composition, so ending the call lands back exactly
 * where you were). Like the viewer it lifts LightOS's forced grayscale for its
 * lifetime; no exit choreography here — a call ends deliberately, and the page
 * is chrome-dark anyway. Two permission layers for camera/mic: Android's runtime
 * CAMERA/RECORD_AUDIO (asked on entry; deny and you can still join listen-only)
 * and the WebView's own [PermissionRequest], granted only to Apple's origin and
 * only for what Android itself granted.
 */
@Composable
fun FaceTimeScreen(url: String, onClose: () -> Unit) {
    val context = LocalContext.current

    var granted by remember { mutableStateOf(hasAvPermissions(context)) }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasAvPermissions(context) }
    LaunchedEffect(Unit) {
        if (!granted) {
            ask.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    // Full color for the duration of the call (vandamd's zero trick; a no-op
    // without the one-time WRITE_SECURE_SETTINGS grant — see ColorMode).
    DisposableEffect(Unit) {
        ColorMode.acquire(context)
        onDispose { ColorMode.release(context) }
    }

    BackHandler { onClose() }

    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            // Tear the page down for real — otherwise the WebRTC session keeps
            // the camera/mic live after the overlay is gone.
            webView?.apply { loadUrl("about:blank"); destroy() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // Remote video should start without a tap.
                    settings.mediaPlaybackRequiresUserGesture = false
                    // Keep navigation inside the view (the join flow redirects
                    // within apple.com; without this it would try a browser).
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onPermissionRequest(request: PermissionRequest) {
                            // Only Apple's call page, and only once Android's own
                            // camera/mic runtime grants are in place.
                            val host = request.origin?.host ?: ""
                            if ((host == "apple.com" || host.endsWith(".apple.com")) &&
                                hasAvPermissions(context)
                            ) {
                                request.grant(request.resources)
                            } else {
                                request.deny()
                            }
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
        )
        // The page has its own leave button; this is the guaranteed exit (same ×
        // as the reply-banner cancel — Public Sans has that glyph).
        HapticText(
            text = "×",
            style = ChatType.title,
            color = ChatColors.onSurface,
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private fun hasAvPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
