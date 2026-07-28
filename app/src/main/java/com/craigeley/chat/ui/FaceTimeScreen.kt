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
import com.craigeley.chat.api.Store
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType
import org.json.JSONObject

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

    // Leaving is confirmed, not instant — a stray tap (or reflexive Back) mid-call
    // shouldn't hang up. First ×/Back arms it ("End?" for 3s), second actually
    // closes. Mirrors the thread header's Call control.
    var closeArmed by remember { mutableStateOf(false) }
    LaunchedEffect(closeArmed) {
        if (closeArmed) {
            kotlinx.coroutines.delay(3_000)
            closeArmed = false
        }
    }
    BackHandler { if (closeArmed) onClose() else closeArmed = true }

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
                    // within apple.com; without this it would try a browser) and
                    // run the helper script once each page settles: auto-fill the
                    // name + Continue, and auto-admit join requests.
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String?) {
                            if ((finishedUrl ?: "").contains("facetime.apple.com")) {
                                view.evaluateJavascript(
                                    faceTimeHelperScript(Store.faceTimeName(context)),
                                    null,
                                )
                            }
                        }
                    }
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
        // as the reply-banner cancel — Public Sans has that glyph). Two-tap: the
        // first arms it, the second (within 3s) actually ends.
        HapticText(
            text = if (closeArmed) "End?" else "×",
            style = ChatType.title,
            color = ChatColors.onSurface,
            onClick = { if (closeArmed) onClose() else closeArmed = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

private fun hasAvPermissions(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/**
 * Best-effort conveniences injected into Apple's call page — both are DOM
 * automation against markup Apple can change, so everything here fails soft
 * back to the manual flow:
 *
 * 1. **Name auto-fill.** The join page asks for a display name every time (its
 *    own memory doesn't reliably survive our WebView teardown). If a name is set
 *    in Settings, type it (React holds the input's state, so it's set through
 *    the native value setter + an `input` event — assigning `.value` directly
 *    wouldn't register), click Continue, **and click the final Join** — fully
 *    automatic entry. Speed matters beyond convenience: the server's window for
 *    auto-admitting *you* starts when the link is minted and expires after 2
 *    minutes, so the sooner the join request lands, the surer the admit. (No
 *    name set → everything stays manual, camera preview included.)
 * 2. **Guest auto-admit.** The server auto-admits only the *first* joiner into a
 *    minted call (BlueBubbles' admitAndLeave admits one, waits 15s, leaves) — so
 *    everyone after that would wait on a manual admit from inside the call. A
 *    MutationObserver (plus a slow sweep, for UI the observer misses) watches
 *    for join-request prompts and clicks anything labelled admit/approve.
 *
 * Idempotent per page (`__ftAuto` guard) — onPageFinished can fire repeatedly.
 */
private fun faceTimeHelperScript(name: String): String {
    // JSONObject.quote gives a fully escaped, quoted JS string literal.
    val jsName = JSONObject.quote(name.trim())
    return """
        (function () {
          if (window.__ftAuto) return; window.__ftAuto = true;
          var name = $jsName;

          function fillName(tries) {
            if (!name) return;
            var input = document.querySelector('input[type="text"]') ||
                        document.querySelector('input:not([type="hidden"])');
            if (!input) {
              if (tries > 0) setTimeout(function () { fillName(tries - 1); }, 500);
              return;
            }
            if (input.value === name) return;
            var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
            setter.call(input, name);
            input.dispatchEvent(new Event('input', { bubbles: true }));
            setTimeout(function () {
              var btn = Array.prototype.slice.call(document.querySelectorAll('button')).find(function (b) {
                return /continue/i.test(b.textContent || '');
              });
              if (btn && !btn.disabled) btn.click();
            }, 400);
          }

          var admitRe = /(admit|approve|let in)/i;
          function scanAdmit(root) {
            if (!root.querySelectorAll) return;
            var btns = root.querySelectorAll('button');
            for (var i = 0; i < btns.length; i++) {
              var b = btns[i];
              var label = (b.getAttribute('aria-label') || '') + ' ' + (b.textContent || '');
              if (admitRe.test(label) && !b.disabled) b.click();
            }
          }

          // With a name set, also click the final Join (one-shot) — a bare
          // "Join" button/label, careful not to match join-request prompts.
          function scanJoin() {
            if (!name || window.__ftJoined) return;
            var btns = document.querySelectorAll('button');
            for (var i = 0; i < btns.length; i++) {
              var b = btns[i];
              var t = (b.textContent || '').trim();
              var a = (b.getAttribute('aria-label') || '').trim();
              if ((/^join$/i.test(t) || /^join$/i.test(a)) && !b.disabled) {
                window.__ftJoined = true;
                b.click();
                return;
              }
            }
          }

          new MutationObserver(function (muts) {
            for (var m = 0; m < muts.length; m++) {
              var added = muts[m].addedNodes;
              for (var n = 0; n < added.length; n++) {
                if (added[n].nodeType === 1) scanAdmit(added[n]);
              }
            }
            scanJoin();
          }).observe(document.documentElement, { childList: true, subtree: true });
          setInterval(function () { scanAdmit(document); scanJoin(); }, 2000);

          fillName(20);
        })();
    """.trimIndent()
}
