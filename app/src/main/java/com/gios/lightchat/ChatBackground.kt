package com.gios.lightchat

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gios.lightchat.api.Store
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A per-chat wallpaper, drawn behind the thread.
 *
 * The image is chosen once (from the same DCIM/Pictures walk the photo picker
 * uses) and then run through a *stack* of filters — each one a simple pixel
 * pass, applied in order, repeatable. On a greyscale panel the interesting ones
 * are the ones that embrace it: an ordered dither quantises the photo down to
 * pure black-and-white halftone (at a chosen cell size, so 8× reads chunky and
 * deliberate rather than like a rendering bug), and Fade pulls the whole thing
 * toward the black the app already paints, which is what keeps white text
 * readable over it. Corner blur melts the edges so the picture sits *behind*
 * the conversation instead of competing with it.
 *
 * Everything is dependency-free pixel work on [Bitmap]s, same as [Attachments]:
 * no render effects, no GPU passes — this phone's panel is 1080 wide and the
 * result is computed once and cached, so a straight IntArray walk is plenty.
 */
object ChatBackground {

    /** One filter application: which effect, and how hard. [amount]'s meaning is
     *  per-type — a dither cell size, a percentage — see [FilterType]. */
    data class Filter(val type: FilterType, val amount: Int)

    /**
     * The four effects, with the range their [Filter.amount] moves in.
     *
     * [stepped] types adjust by +/- [step] between [min] and [max]; DITHER instead
     * doubles/halves (2 → 4 → 8 → 16), because halftone cells read in octaves —
     * the step from 7 to 8 is invisible where 4 to 8 is the whole point.
     */
    enum class FilterType(
        val label: String,
        val min: Int,
        val max: Int,
        val step: Int,
        val default: Int,
    ) {
        /** Ordered Bayer dither to pure black/white, [Filter.amount] = cell size in px. */
        DITHER("Dither", 1, 16, 0, 8),

        /** Plain luminance greyscale. No amount — it either is or isn't. */
        MONO("Black & white", 0, 0, 0, 0),

        /** How much of the image survives over the black behind it, in percent.
         *  40 means the picture is drawn at 40% and the rest is background. */
        FADE("Opacity", 10, 90, 10, 40),

        /** Blur that grows from a sharp centre out to the corners, percent strength. */
        CORNER_BLUR("Corner blur", 10, 100, 10, 50),
        ;

        val hasAmount: Boolean get() = min != max

        /** How the amount reads in a row: "8×" for a cell size, "40%" for the rest. */
        fun display(amount: Int): String = when (this) {
            DITHER -> "$amount×"
            MONO -> ""
            else -> "$amount%"
        }

        fun bump(amount: Int, up: Boolean): Int = when (this) {
            DITHER -> if (up) min(max, amount * 2) else max(min, amount / 2)
            else -> if (up) min(max, amount + step) else max(min, amount - step)
        }
    }

    /** Processed backgrounds held decoded; keyed by guid + the exact stack, so an
     *  edit is a different key and the stale one just ages out. */
    private val cache = object : LruCache<String, ImageBitmap>(3) {}

    /** Bumped on every save/remove; the thread keys its load on it, which is what
     *  makes a background change show up without reopening the chat. */
    val version = mutableIntStateOf(0)

    /** Where the chosen original lives, config aside — presence of this file is
     *  what "this chat has a background" means. */
    fun sourceFile(context: Context, chatGuid: String): File =
        File(File(context.filesDir, "backgrounds").apply { mkdirs() }, safeName(chatGuid))

    fun has(context: Context, chatGuid: String): Boolean =
        sourceFile(context, chatGuid).length() > 0L

    /** The saved filter stack, oldest-applied first. Empty when none saved. */
    fun filters(context: Context, chatGuid: String): List<Filter> {
        val json = Store.background(context, chatGuid) ?: return emptyList()
        return runCatching {
            val array = JSONObject(json).getJSONArray("filters")
            (0 until array.length()).mapNotNull { i ->
                val o = array.getJSONObject(i)
                val type = FilterType.entries.firstOrNull { it.name == o.getString("type") }
                type?.let { Filter(it, o.optInt("amount", it.default).coerceIn(it.min, it.max)) }
            }
        }.getOrDefault(emptyList())
    }

    /** Persists [source] (copied, so a photo later deleted from DCIM keeps working)
     *  and the stack, then bumps [version] so open screens reload. */
    suspend fun save(context: Context, chatGuid: String, source: File, filters: List<Filter>) {
        withContext(Dispatchers.IO) {
            val dest = sourceFile(context, chatGuid)
            if (source.canonicalPath != dest.canonicalPath) {
                runCatching { source.copyTo(dest, overwrite = true) }
            }
            Store.setBackground(context, chatGuid, JSONObject().apply {
                put("filters", JSONArray().apply {
                    filters.forEach {
                        put(JSONObject().apply {
                            put("type", it.type.name)
                            put("amount", it.amount)
                        })
                    }
                })
            }.toString())
        }
        version.intValue++
    }

    fun remove(context: Context, chatGuid: String) {
        sourceFile(context, chatGuid).delete()
        Store.setBackground(context, chatGuid, null)
        version.intValue++
    }

    /**
     * The finished background for [chatGuid] at roughly the screen's shape, or
     * null when the chat has none. Cached decoded; the pixel passes only run when
     * the stack or the photo changed.
     */
    suspend fun load(context: Context, chatGuid: String, aspect: Float): ImageBitmap? {
        val file = sourceFile(context, chatGuid)
        if (file.length() == 0L) return null
        val stack = filters(context, chatGuid)
        val key = chatGuid + "|" + aspect + "|" + stack.joinToString { "${it.type.name}:${it.amount}" }
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            val bitmap = render(file, stack, aspect, THREAD_DIM) ?: return@withContext null
            val image = bitmap.asImageBitmap()
            cache.put(key, image)
            image
        }
    }

    /** As [load] but small and uncached — the editor's live preview, re-run on
     *  every stack change. */
    suspend fun preview(file: File, filters: List<Filter>, aspect: Float): ImageBitmap? =
        withContext(Dispatchers.Default) {
            render(file, filters, aspect, PREVIEW_DIM)?.asImageBitmap()
        }

    // ---- the pipeline ----

    private fun render(file: File, filters: List<Filter>, aspect: Float, maxDim: Int): Bitmap? {
        val decoded = Attachments.decode(file, maxDim) ?: return null
        var bitmap = centerCrop(decoded, aspect)
        for (filter in filters) {
            bitmap = when (filter.type) {
                FilterType.MONO -> mono(bitmap)
                FilterType.FADE -> fade(bitmap, filter.amount)
                FilterType.DITHER -> dither(bitmap, filter.amount)
                FilterType.CORNER_BLUR -> cornerBlur(bitmap, filter.amount)
            }
        }
        return bitmap
    }

    /** Crop to the screen's aspect before filtering, not after: corner blur has to
     *  put its corners where the *screen's* corners will be. */
    private fun centerCrop(src: Bitmap, aspect: Float): Bitmap {
        if (aspect <= 0f) return src
        val srcAspect = src.width.toFloat() / src.height
        return if (srcAspect > aspect) {
            val w = (src.height * aspect).toInt().coerceIn(1, src.width)
            Bitmap.createBitmap(src, (src.width - w) / 2, 0, w, src.height)
        } else {
            val h = (src.width / aspect).toInt().coerceIn(1, src.height)
            Bitmap.createBitmap(src, 0, (src.height - h) / 2, src.width, h)
        }
    }

    private fun mono(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val g = gray(pixels[i])
            pixels[i] = 0xFF shl 24 or (g shl 16) or (g shl 8) or g
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /** Scales every channel toward black — the app's background — by 100−[amount]%. */
    private fun fade(src: Bitmap, amount: Int): Bitmap {
        val keep = amount.coerceIn(0, 100)
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = ((p shr 16 and 0xFF) * keep) / 100
            val g = ((p shr 8 and 0xFF) * keep) / 100
            val b = ((p and 0xFF) * keep) / 100
            pixels[i] = 0xFF shl 24 or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /**
     * Ordered Bayer dither to pure black-and-white at a [cell]-pixel halftone cell.
     *
     * Done by working at 1/cell scale and blowing back up with no filtering, so an
     * 8× dither is literally 8×8 blocks of solid black or white — the chunky,
     * deliberate look — rather than a fine dither that the panel would smear.
     */
    private fun dither(src: Bitmap, cell: Int): Bitmap {
        val c = cell.coerceIn(1, 32)
        val w = max(1, src.width / c)
        val h = max(1, src.height / c)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val g = gray(pixels[row + x])
                val threshold = (BAYER_8[y and 7][x and 7] * 255 + 32) / 64
                pixels[row + x] = if (g > threshold) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        return Bitmap.createScaledBitmap(small, src.width, src.height, false)
    }

    /**
     * Blur rising from a sharp centre to fully blurred corners.
     *
     * The blurred copy is the cheap classic — downscale bilinear, upscale bilinear —
     * which at 1/16 scale is a heavy, smooth blur with no kernel code to get wrong.
     * [amount] moves both how strong that blur is and how far toward the centre it
     * starts reaching.
     */
    private fun cornerBlur(src: Bitmap, amount: Int): Bitmap {
        val strength = amount.coerceIn(1, 100)
        // 1/6 scale at 10% up to 1/20 at 100% — visibly soft either way, heavier with more.
        val factor = 6 + (strength * 14) / 100
        val small = Bitmap.createScaledBitmap(
            src,
            max(1, src.width / factor),
            max(1, src.height / factor),
            true,
        )
        val blurred = Bitmap.createScaledBitmap(small, src.width, src.height, true)

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val sharp = IntArray(w * h)
        val soft = IntArray(w * h)
        out.getPixels(sharp, 0, w, 0, 0, w, h)
        blurred.getPixels(soft, 0, w, 0, 0, w, h)

        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f
        val maxDist = sqrt(cx * cx + cy * cy)
        // Where the blur begins, as a fraction of the way from centre to corner:
        // gentle amounts keep most of the frame sharp, full strength reaches halfway in.
        val start = 0.85f - 0.55f * (strength / 100f)
        for (y in 0 until h) {
            val row = y * w
            val dy = (y - cy) / maxDist
            for (x in 0 until w) {
                val dx = (x - cx) / maxDist
                val d = sqrt(dx * dx + dy * dy)
                var t = ((d - start) / (1f - start)).coerceIn(0f, 1f)
                t *= t // ease in, so the transition has no visible ring
                if (t <= 0f) continue
                val a = sharp[row + x]
                val b = soft[row + x]
                val ti = (t * 256).toInt()
                val r = ((a shr 16 and 0xFF) * (256 - ti) + (b shr 16 and 0xFF) * ti) shr 8
                val g = ((a shr 8 and 0xFF) * (256 - ti) + (b shr 8 and 0xFF) * ti) shr 8
                val bl = ((a and 0xFF) * (256 - ti) + (b and 0xFF) * ti) shr 8
                sharp[row + x] = 0xFF shl 24 or (r shl 16) or (g shl 8) or bl
            }
        }
        out.setPixels(sharp, 0, w, 0, 0, w, h)
        return out
    }

    private fun gray(pixel: Int): Int =
        ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000

    /** The standard 8×8 Bayer matrix, values 0..63. */
    private val BAYER_8 = arrayOf(
        intArrayOf(0, 32, 8, 40, 2, 34, 10, 42),
        intArrayOf(48, 16, 56, 24, 50, 18, 58, 26),
        intArrayOf(12, 44, 4, 36, 14, 46, 6, 38),
        intArrayOf(60, 28, 52, 20, 62, 30, 54, 22),
        intArrayOf(3, 35, 11, 43, 1, 33, 9, 41),
        intArrayOf(51, 19, 59, 27, 49, 17, 57, 25),
        intArrayOf(15, 47, 7, 39, 13, 45, 5, 37),
        intArrayOf(63, 31, 55, 23, 61, 29, 53, 21),
    )

    /** Full-quality size for the thread itself; the panel is 1080 wide. */
    private const val THREAD_DIM = 1080

    /** The editor preview re-renders on every tap of −/+, so it works small. */
    private const val PREVIEW_DIM = 480

    private fun safeName(guid: String): String =
        guid.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")
}
