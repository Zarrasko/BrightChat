package com.gios.lightchat.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [Painter] that plays an animated GIF, with no image library involved.
 *
 * ### Why by hand
 *
 * The rest of this app decodes stills through [android.graphics.BitmapFactory]
 * ([com.gios.lightchat.Attachments]) — which, handed a GIF, silently returns its **first frame**.
 * That is why a GIF arriving in a thread has always looked like a photograph of a GIF. The
 * platform has known how to play one since API 28 ([ImageDecoder] returns an
 * [AnimatedImageDrawable] for an animated file), and this phone is on 34, so the animation costs
 * a decoder call and a painter — not Coil, not Glide, and no Google dependency, which is the whole
 * point of this app.
 *
 * ### The parts that are load-bearing
 *
 * - **Decoding happens off the main thread.** [ImageDecoder] does the full decode synchronously,
 *   and a grid of them on the main thread is a visibly stalled screen.
 * - **[AnimatedImageDrawable] repaints by calling back, not by ticking.** It invalidates itself on
 *   its own schedule and expects a [Drawable.Callback] to turn that into a redraw. Without one it
 *   draws its first frame and then sits there — the same symptom as no animation at all, from a
 *   completely different cause. The callback bumps a snapshot counter that [DrawScope.onDraw]
 *   reads, which is what makes Compose re-run the draw.
 * - **It only animates on a hardware canvas.** Which the window is; but this is the reason the
 *   drawable is drawn into [DrawScope.drawIntoCanvas]'s native canvas rather than being converted
 *   to a bitmap once.
 * - **Started and stopped with the composition**, via [RememberObserver]. A picker scrolled past
 *   holds a decoded drawable for a moment either way, and one still animating off-screen is
 *   frames nobody sees, drawn on a battery that has to last the day.
 */
@Composable
fun rememberGifPainter(file: File?, maxDim: Int = GIF_MAX_DIM): Painter? {
    val decoded by produceState<Drawable?>(null, file?.path, maxDim) {
        value = file?.let { decodeAnimated(it, maxDim) }
    }
    val drawable = decoded ?: return null
    return remember(drawable) { DrawablePainter(drawable) }
}

/**
 * Cap for a decoded GIF's long edge.
 *
 * Lower than the 1080px [com.gios.lightchat.Attachments] uses for photographs, and deliberately:
 * an animated drawable holds frames, not a frame, so the same cap costs several times the memory
 * — and a GIF is a low-resolution thing to begin with. 720px is more than the panel can show of
 * one inside a message column.
 */
const val GIF_MAX_DIM = 720

/**
 * Decodes [file] as an animated drawable, downsampled to [maxDim] and set to loop forever.
 *
 * Returns a still drawable for a single-frame GIF (which plenty of "GIFs" are) and null for
 * anything that will not decode — a truncated download, a file a provider served as HTML.
 */
private suspend fun decodeAnimated(file: File, maxDim: Int): Drawable? = withContext(Dispatchers.IO) {
    if (!file.isFile || file.length() == 0L) return@withContext null
    runCatching {
        val drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxDim) {
                // Powers of two, like inSampleSize: the decoder's own sample-size path is the
                // cheap one, and a GIF's frames are decoded repeatedly.
                var sample = 1
                while (longest / (sample * 2) >= maxDim) sample *= 2
                decoder.setTargetSampleSize(sample)
            }
        }
        (drawable as? AnimatedImageDrawable)?.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        drawable
    }.getOrNull()
}

/**
 * Draws a [Drawable] — animating, if it is one — as a Compose [Painter].
 *
 * Small on purpose: this exists because the one thing in the app that needs it is a GIF, and a
 * general drawable-to-painter bridge is a dependency (Accompanist's) for a file this size.
 */
private class DrawablePainter(private val drawable: Drawable) : Painter(), RememberObserver {

    /**
     * Bumped on every invalidation from the drawable and read in [onDraw].
     *
     * The read is what subscribes this painter's draw to the counter, so a frame arriving turns
     * into a redraw. It is not "unused" and must not be tidied away.
     */
    private var invalidations by mutableIntStateOf(0)

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) {
            invalidations++
        }

        // AnimatedImageDrawable drives itself, but a drawable is entitled to ask to be run
        // later, and a Callback that ignores that is one that stops such a drawable dead.
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            handler.postAtTime(what, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what)
        }
    }

    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        } else {
            // Unspecified rather than zero: a painter reporting no size is drawn at whatever the
            // layout gives it, which is what a GIF of unknown dimensions should do.
            Size.Unspecified
        }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        drawable.colorFilter = colorFilter?.asAndroidColorFilter()
        return true
    }

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            @Suppress("UNUSED_EXPRESSION")
            invalidations // subscribes this draw to the drawable's invalidations — see above
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            drawable.draw(canvas.nativeCanvas)
        }
    }

    override fun onRemembered() {
        drawable.callback = callback
        (drawable as? AnimatedImageDrawable)?.start()
    }

    override fun onAbandoned() = stop()

    override fun onForgotten() = stop()

    private fun stop() {
        (drawable as? AnimatedImageDrawable)?.stop()
        drawable.callback = null
    }
}
