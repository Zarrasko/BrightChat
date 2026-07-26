package com.craigeley.chat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.material3.Text
import com.craigeley.chat.Attachment
import com.craigeley.chat.ColorMode
import kotlinx.coroutines.delay
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType

/** How far pinch/double-tap zoom may go. The decoded bitmap is capped at 1080px
 *  on its long edge (see [com.craigeley.chat.Attachments]), so past ~4× there's
 *  no detail left to reveal on this screen anyway. */
private const val MAX_SCALE = 4f

/** The scale a double-tap jumps to (a second double-tap returns to fit). */
private const val DOUBLE_TAP_SCALE = 2.5f

/** Dismissal: how long the photo takes to fade out to the black background. */
private const val FADE_OUT_MS = 120

/** Dismissal: how long to hold the all-black frame after the grayscale restore
 *  before revealing the thread — the settings write propagates asynchronously
 *  (system_server → SurfaceFlinger), so give it a few frames to land while the
 *  screen is still black and the flip is invisible. */
private const val RESTORE_SETTLE_MS = 70L

/**
 * Full-screen viewer for one image attachment, opened by tapping it in the
 * thread. Same loader (and caches) as the inline rendering, so it appears
 * instantly. Pinch to zoom, drag to pan while zoomed, double-tap to toggle
 * zoom at the tapped point; a single tap — or Back — closes it. Pure black
 * behind the image, no chrome: the LightOS look.
 */
@Composable
fun ImageViewerScreen(
    attachment: Attachment,
    loadImage: suspend (Attachment) -> ImageBitmap?,
    onClose: () -> Unit,
) {
    // True color for exactly as long as the viewer is up (vandamd's zero trick;
    // see ColorMode — a no-op without the one-time WRITE_SECURE_SETTINGS grant).
    // Backgrounding mid-view is handled by MainActivity's onStop/onStart.
    val context = LocalContext.current
    DisposableEffect(Unit) {
        ColorMode.acquire(context)
        // Normally already released mid-close (below); this catches the viewer
        // being disposed some other way (e.g. the whole thread closing).
        onDispose { ColorMode.release(context) }
    }

    // Closing plays a short exit so the grayscale flip can't be seen: fade the
    // photo out to the black background, restore grayscale while the screen is
    // pure black (black is identical in color and mono — the one moment the flip
    // is invisible), hold a few frames for the flip to land, then dismiss. The
    // thread appears already-B&W; nothing on screen ever visibly desaturates.
    var closing by remember { mutableStateOf(false) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(closing) {
        if (!closing) return@LaunchedEffect
        fade.animateTo(0f, tween(FADE_OUT_MS))
        ColorMode.release(context)
        delay(RESTORE_SETTLE_MS)
        onClose()
    }

    BackHandler { closing = true }

    val bitmap by produceState<ImageBitmap?>(initialValue = null, attachment.guid) {
        value = loadImage(attachment)
    }

    // The zoom/pan transform: the image is drawn scaled by [scale] about the
    // screen centre, then shifted by [offset] (screen pixels).
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }

    // Keep the image from being dragged fully off-screen: the translation is
    // bounded by how much the scaled content overhangs the container. (Bounded
    // on the container, not the fitted image, so a letterboxed photo can be
    // panned edge-to-edge — simple and good enough at these sizes.)
    fun clamp(o: Offset, s: Float): Offset {
        val maxX = container.width * (s - 1f) / 2f
        val maxY = container.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChatColors.background)
            .onSizeChanged { container = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { closing = true },
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = DOUBLE_TAP_SCALE
                            // Pull the tapped point toward the centre of the screen.
                            offset = clamp((center - tap) * (DOUBLE_TAP_SCALE - 1f), DOUBLE_TAP_SCALE)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    // Keep the content point under the fingers fixed while the
                    // scale changes, then apply the drag. (graphicsLayer scales
                    // about the centre then translates, so a content point q —
                    // relative to centre — lands at s·q + offset.)
                    val newOffset = (centroid - center) - (centroid - center - offset) * (newScale / scale) + pan
                    offset = clamp(newOffset, newScale)
                    scale = newScale
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = attachment.transferName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                        alpha = fade.value
                    },
            )
        } else {
            Text(text = "[Image]", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        }
    }
}
