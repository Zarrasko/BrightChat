package com.gios.lightchat

import android.Manifest
import android.os.Environment
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's own photos, read straight off the filesystem.
 *
 * This exists because the system photo picker is useless here. It reads MediaStore,
 * and nothing on LightOS keeps MediaStore current — there's no media provider doing
 * the scanning a normal Android build does — so photos taken minutes ago simply
 * aren't offered. Walking DCIM and Pictures ourselves can't go stale: the directory
 * listing *is* the source of truth, and a photo is visible the moment it's written.
 *
 * Reading other apps' image files by path needs `READ_MEDIA_IMAGES` (a normal
 * runtime prompt on 33+, which is also what makes the direct path read legal).
 */
object Gallery {

    /** How many photos the grid will hold. The LPIII's camera roll is not a phone
     *  library — this is a generous ceiling that keeps the scan and the list cheap. */
    private const val MAX_PHOTOS = 600

    /** Grid cells are a third of the ~384dp inside the screen's gutters, so ~113dp —
     *  about 288px at the panel's 2.55 density. 256 is a touch under that, which is
     *  invisible on thumbnails and decodes a step faster. */
    private const val THUMB_DIM = 256

    /** 12MB of thumbnails. Sized in bytes, not entries: a count-based cache of ~190KB
     *  bitmaps quietly retains tens of megabytes for the life of the process, on top of
     *  the full-size cache in [Attachments]. */
    private const val THUMB_CACHE_BYTES = 12 * 1024 * 1024

    private val thumbnails = object : LruCache<String, ImageBitmap>(THUMB_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** What the camera and screenshots actually write. HEIC included because it's
     *  what a lot of phone cameras default to now; BlueBubbles takes it as-is. */
    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")

    val permission: String = Manifest.permission.READ_MEDIA_IMAGES

    /** One photo on disk. [takenAt] is the file's mtime — EXIF would be more correct
     *  but means opening every file to sort the grid, and for a camera roll the two
     *  agree. */
    data class Photo(val file: File, val takenAt: Long) {
        val key: String get() = file.path
    }

    /**
     * Every image under DCIM and Pictures, newest first. Off-main: this touches the
     * filesystem. Returns empty rather than throwing when the permission is missing
     * — the caller is showing a prompt in that case anyway.
     */
    suspend fun scan(): List<Photo> = withContext(Dispatchers.IO) {
        roots()
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    // Deep enough for DCIM/Camera and Pictures/Screenshots; not so
                    // deep that a stray folder of assets turns into a long walk.
                    .maxDepth(3)
                    // .thumbnails holds the launcher's own cached crops — junk here.
                    .onEnter { !it.name.startsWith(".") }
                    .filter {
                        it.isFile && it.length() > 0L &&
                            // .trashed-* and .pending-* are MediaProvider's own
                            // bookkeeping and pass the extension filter otherwise.
                            !it.name.startsWith(".") &&
                            it.extension.lowercase() in EXTENSIONS
                    }
                    .toList()
            }
            .map { Photo(it, it.lastModified()) }
            .sortedByDescending { it.takenAt }
            .take(MAX_PHOTOS)
    }

    /** A downsampled, EXIF-corrected thumbnail, or null if the file won't decode
     *  (a partially-written camera file, most likely). Cached in memory. */
    suspend fun thumbnail(photo: Photo): ImageBitmap? {
        thumbnails.get(photo.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val image = Attachments.decode(photo.file, THUMB_DIM)?.asImageBitmap()
                ?: return@withContext null
            thumbnails.put(photo.key, image)
            image
        }
    }

    /**
     * DCIM and Pictures. `getExternalStoragePublicDirectory` is deprecated in favour
     * of MediaStore, which is precisely the thing that doesn't work on this phone, so
     * the deprecation is noted and ignored.
     */
    @Suppress("DEPRECATION")
    private fun roots(): List<File> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
    )
}
