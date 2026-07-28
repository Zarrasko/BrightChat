package com.craigeley.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Environment
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Nudges MediaStore before the system photo picker opens.
 *
 * The picker reads MediaStore's index, not the filesystem. On the Light Phone III
 * there's nothing keeping that index fresh — no Google Photos / Play-services
 * media jobs, and the camera tool writes files to disk without inserting rows —
 * so the picker keeps showing only whatever was indexed long ago (typically the
 * device's initial scan): the "only sees images from before the app was
 * installed" bug. The fix is to tell MediaStore about the new files ourselves:
 * walk DCIM + Pictures for images newer than the previous sweep and hand them to
 * [MediaScannerConnection] right before launching the picker, which then syncs
 * from the now-current MediaStore.
 *
 * Direct file-path reads of shared storage need [Manifest.permission.READ_MEDIA_IMAGES]
 * (asked once, on the first "+"); denied, [rescan] quietly no-ops — the picker
 * itself needs no permission, it just stays stale.
 */
object MediaRescan {
    private const val PREFS = "media_rescan"
    private const val KEY_LAST = "last_scan"  // lastModified watermark of the previous sweep
    private const val MAX_FILES = 500         // cap one sweep; the watermark catches the rest next time
    private const val TIMEOUT_MS = 4_000L     // don't hold the picker hostage on a huge first scan
    private val EXTENSIONS =
        setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng")

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Scans image files newer than the previous sweep into MediaStore, returning
     * once the scanner has finished with them (or after [TIMEOUT_MS] — scanning
     * continues in MediaProvider's process either way, this just stops blocking
     * the picker launch). Cheap when there's nothing new: two directory walks
     * comparing timestamps, no scanner call.
     */
    suspend fun rescan(context: Context) = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST, 0L)
        val started = System.currentTimeMillis()

        val roots = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        )
        val fresh = ArrayList<String>()
        outer@ for (root in roots) {
            for (f in root.walkTopDown()) {
                if (!f.isFile) continue
                if (f.extension.lowercase() !in EXTENSIONS) continue
                if (f.lastModified() <= since) continue
                fresh.add(f.absolutePath)
                if (fresh.size >= MAX_FILES) break@outer
            }
        }

        if (fresh.isNotEmpty()) {
            withTimeoutOrNull(TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    val remaining = AtomicInteger(fresh.size)
                    MediaScannerConnection.scanFile(context, fresh.toTypedArray(), null) { _, _ ->
                        if (remaining.decrementAndGet() == 0 && cont.isActive) cont.resume(Unit)
                    }
                }
            }
        }
        // Advance the watermark only past what we actually swept: if the cap hit,
        // leave it so the next "+" picks up the remainder.
        prefs.edit().putLong(KEY_LAST, if (fresh.size >= MAX_FILES) since else started).apply()
    }
}
