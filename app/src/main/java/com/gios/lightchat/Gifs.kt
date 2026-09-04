package com.gios.lightchat

import android.content.Context
import com.gios.lightchat.api.KlipyApi
import com.gios.lightchat.api.Store
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The GIFs this phone keeps: the saved ones, the recently sent ones, and the files behind both.
 *
 * ### Saving is keeping the file, not bookmarking a URL
 *
 * A favourite here is a **copy of the GIF under `filesDir`**, not a link to somebody's CDN. Three
 * reasons, in order of how much they matter:
 *
 *  - The picker's Saved tab has to work with the tunnel down and with no API key at all. A saved
 *    GIF is the one thing in this feature that is *yours*, and it should not stop existing because
 *    a search endpoint is unreachable.
 *  - GIF providers lose GIFs. Items get pulled, re-slugged, or — as the whole of 2026 demonstrated
 *    — switched off wholesale. A bookmark to a dead URL is a broken favourite; a file is a file.
 *  - Sending a saved GIF then costs nothing: the bytes are already on the phone.
 *
 * Recents are the opposite trade on purpose: metadata only, in the cache. Something you sent once
 * is worth offering again for a while, and is not worth holding disk for forever.
 *
 * ### Where the bytes live
 *
 * `filesDir/gifs` for saved ones (backed up by nothing — see `backup/Backup.kt`, only `settings`
 * is a LightSync store — but never evicted by Android either), `cacheDir/gifs` for everything
 * else, which the system may clear whenever it likes and which [prune] keeps bounded regardless.
 * Both keyed by a hash of the URL, so the same GIF picked twice is one file.
 */
object Gifs {

    /** How many recently-sent GIFs to keep offering. Two rows of the grid — enough that the one
     *  you send constantly is always right there, few enough that the tab stays scannable. */
    private const val RECENTS = 24

    /** The cache ceiling. GIFs are a couple of hundred kilobytes to a couple of megabytes; this
     *  is the point past which the oldest are dropped rather than letting a browsing session grow
     *  without limit on a phone with little to spare. */
    private const val CACHE_BYTES = 24L * 1024 * 1024

    /** As [Attachments.downloads], and for the same reason: every visible cell asks for its own
     *  file on the frame it composes, and unbounded that is twenty simultaneous requests of which
     *  none finishes quickly. */
    private val downloads = Semaphore(4)

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** A GIF larger than this is refused rather than downloaded. Nothing legitimate in a GIF
     *  picker is this big; a rendition mis-ranked as "medium" can be. */
    private const val MAX_BYTES = 20L * 1024 * 1024

    // -------------------------------------------------------------------------------- the lists

    fun saved(context: Context): List<Gif> = Store.savedGifs(context)

    fun isSaved(context: Context, gif: Gif): Boolean = saved(context).any { it.id == gif.id }

    /**
     * Saves or unsaves [gif], returning whether it is saved afterwards.
     *
     * Newest first, like [Store.pins]: the thing you just saved is the thing you are about to
     * look for. Unsaving deletes the kept file — a saved GIF is the only thing holding one, so
     * leaving it behind would be a slow leak of exactly the files nothing else ever cleans up.
     */
    fun toggleSaved(context: Context, gif: Gif): Boolean {
        val current = saved(context)
        val already = current.any { it.id == gif.id }
        if (already) {
            Store.setSavedGifs(context, current.filterNot { it.id == gif.id })
            runCatching { kept(context, gif).delete() }
            return false
        }
        Store.setSavedGifs(context, listOf(gif) + current.filterNot { it.id == gif.id })
        return true
    }

    fun recents(context: Context): List<Gif> = Store.recentGifs(context)

    /** Records a GIF as just sent: to the front, deduplicated by id, oldest past [RECENTS] dropped. */
    fun remember(context: Context, gif: Gif) {
        val next = (listOf(gif) + recents(context).filterNot { it.id == gif.id }).take(RECENTS)
        Store.setRecentGifs(context, next)
    }

    // -------------------------------------------------------------------------------- the files

    /**
     * The local file for [gif]'s sendable rendition, downloading it if this phone hasn't got it.
     *
     * A saved GIF resolves to its kept copy under `filesDir` and never goes to the network. Runs
     * entirely off the main thread; null means the GIF could not be fetched, which the caller has
     * to say out loud rather than sending nothing.
     */
    suspend fun file(context: Context, gif: Gif): File? = fetch(context, gif, gif.sendUrl, saveable = true)

    /** As [file], for the small rendition the grid draws. Never promoted to a kept copy: the
     *  preview is a thumbnail, and saving a GIF has to keep the one you would send. */
    suspend fun preview(context: Context, gif: Gif): File? = fetch(context, gif, gif.previewUrl, saveable = false)

    /**
     * The file a grid cell should draw: the kept copy for a saved GIF, the small rendition
     * otherwise.
     *
     * The distinction is the whole point of saving. A saved GIF has its real file on this phone,
     * so its cell draws with no request at all — which is what makes the Saved tab work on a
     * plane, with the tunnel down, or with no API key in the app.
     */
    suspend fun thumb(context: Context, gif: Gif): File? =
        if (isSaved(context, gif)) file(context, gif) else preview(context, gif)

    private suspend fun fetch(context: Context, gif: Gif, url: String, saveable: Boolean): File? {
        if (url.isBlank()) return null
        return withContext(Dispatchers.IO) {
            // A kept copy answers first, without a permit and without a request: this is the
            // path the Saved tab and every re-send take, and it must work with no network.
            if (saveable) {
                val kept = kept(context, gif)
                if (kept.isFile && kept.length() > 0L) return@withContext kept
            }
            val cached = File(cacheDir(context), name(url))
            if (cached.isFile && cached.length() > 0L) {
                return@withContext promoteIfSaved(context, gif, cached, saveable)
            }
            val ok = downloads.withPermit {
                // Re-checked inside the permit: the grid and a send can queue for the same file,
                // and the second would otherwise download over what the first just wrote.
                if (cached.isFile && cached.length() > 0L) true else download(url, cached)
            }
            if (!ok) return@withContext null
            prune(context)
            promoteIfSaved(context, gif, cached, saveable)
        }
    }

    /**
     * If [gif] is saved, moves the freshly-downloaded [cached] file into the kept directory.
     *
     * Saving happens in the picker, where the *preview* is on screen and the sendable rendition
     * may not have been downloaded yet — so the copy that makes a favourite offline cannot be
     * made at the moment it is saved. It is made here instead, the first time the real file
     * exists, which is at the latest when the GIF is sent.
     */
    private fun promoteIfSaved(context: Context, gif: Gif, cached: File, saveable: Boolean): File {
        if (!saveable || !isSaved(context, gif)) return cached
        val kept = kept(context, gif)
        if (kept.isFile && kept.length() > 0L) return kept
        return runCatching {
            kept.parentFile?.mkdirs()
            cached.copyTo(kept, overwrite = true)
            kept
        }.getOrDefault(cached)
    }

    private fun download(url: String, dest: File): Boolean {
        val conn = runCatching {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
            }
        }.getOrNull() ?: return false
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return false
            val declared = conn.contentLengthLong
            if (declared > MAX_BYTES) return false
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            // Checked after the fact as well: a chunked response declares no length, so the only
            // way to know it was reasonable is to have written it.
            if (dest.length() !in 1..MAX_BYTES) {
                dest.delete()
                false
            } else {
                true
            }
        } catch (_: Exception) {
            // Never leave a truncated file behind: it would satisfy every "have we got it?"
            // check above and then fail to decode, forever.
            dest.delete()
            false
        } finally {
            conn.disconnect()
        }
    }

    /** Drops the oldest cached GIFs once the directory is over [CACHE_BYTES]. Kept copies are in
     *  another directory and are not touched. */
    private fun prune(context: Context) {
        runCatching {
            val files = cacheDir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (file in files) {
                if (total <= CACHE_BYTES) return
                total -= file.length()
                file.delete()
            }
        }
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, "gifs")

    private fun keptDir(context: Context): File = File(context.filesDir, "gifs")

    private fun kept(context: Context, gif: Gif): File = File(keptDir(context), name(gif.sendUrl))

    /** A filename for a URL: its hash, so two renditions of the same GIF are two files and the
     *  same rendition asked for twice is one. Not the URL's own last path segment — providers
     *  serve half their renditions as `.gif` under identical names in different directories. */
    private fun name(url: String): String = Integer.toHexString(url.hashCode()) + "_" + url.length + ".gif"

    // ------------------------------------------------------------------------------ the service

    /** The search client, or null when no key has been set — in which case the picker offers the
     *  saved and recent tabs and says what is missing, rather than showing an error. */
    fun api(context: Context): KlipyApi? = Store.klipyKey(context).takeIf { it.isNotBlank() }?.let { KlipyApi(it) }

    /**
     * Tells the provider a GIF went out. Best-effort and deliberately silent — see
     * [KlipyApi.registerShare]. Called after the send, never before it.
     */
    fun reportShare(context: Context, gif: Gif) {
        runCatching { api(context)?.registerShare(gif.id, Store.gifCustomerId(context)) }
    }
}
