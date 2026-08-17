package com.gios.lightchat

import android.Manifest
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's own photos and videos, read straight off the filesystem.
 *
 * This exists because the system photo picker is useless here. It reads MediaStore,
 * and nothing on LightOS keeps MediaStore current — there's no media provider doing
 * the scanning a normal Android build does — so photos taken minutes ago simply
 * aren't offered. Walking DCIM and Pictures ourselves can't go stale: the directory
 * listing *is* the source of truth, and a photo is visible the moment it's written.
 *
 * Reading other apps' media files by path needs `READ_MEDIA_IMAGES` *and*
 * `READ_MEDIA_VIDEO` (normal runtime prompts on 33+, which is also what makes the
 * direct path read legal). The two are separate grants, and Android 13+ shows them
 * as one dialog only when they're requested together — hence [permissions] rather
 * than a single string.
 */
object Gallery {

    /** How many items the grid will hold. The LPIII's camera roll is not a phone
     *  library — this is a generous ceiling that keeps the scan and the list cheap. */
    private const val MAX_ITEMS = 600

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
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "gif", "bmp")

    /**
     * Video containers worth offering.
     *
     * `mov` first because it is what an iPhone writes and what the other end of an
     * iMessage thread is most likely to be sending back. `3gp` is here because some
     * Android camera stacks still default to it; `mkv` deliberately is not — iMessage
     * will carry the file but nothing on the receiving end will open it, so offering
     * it would only produce sends that silently fail to play.
     */
    private val VIDEO_EXTENSIONS = setOf("mov", "mp4", "m4v", "3gp")

    /**
     * Both media grants, requested as one array.
     *
     * Asking for them one after another produces two dialogs back to back, which on a
     * phone with this screen reads as the app having been denied and asking again.
     */
    val permissions: Array<String> = arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
    )

    /** Stills only. The background editor picks a wallpaper, which can never be a
     *  clip, so it asks for the one grant it can actually use rather than making the
     *  user hand over their video library to choose a backdrop. */
    val permission: String = Manifest.permission.READ_MEDIA_IMAGES

    /** What a picked item is. Kept on [Photo] rather than derived from the extension
     *  at each call site, because the send path, the thumbnailer and the grid badge
     *  all need to branch on it. */
    enum class Kind { IMAGE, VIDEO }

    /**
     * One item on disk. [takenAt] is the file's mtime — EXIF would be more correct
     * but means opening every file to sort the grid, and for a camera roll the two
     * agree. [durationMs] is 0 for stills and is filled lazily by [thumbnail] for
     * video, since reading it up front would mean opening every clip during the scan.
     */
    data class Photo(
        val file: File,
        val takenAt: Long,
        val kind: Kind = Kind.IMAGE,
    ) {
        val key: String get() = file.path
        val isVideo: Boolean get() = kind == Kind.VIDEO
    }

    /**
     * Durations discovered while thumbnailing, keyed by path. Read by the grid to draw
     * its badge; absent until that clip's cell has composed once.
     *
     * Concurrent, not a plain map: several cells thumbnail at once on IO threads while
     * composition reads this on the main one, which on a `LinkedHashMap` is how you get
     * an occasional `ConcurrentModificationException` from a scroll.
     */
    private val durations = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** The clip's length as `m:ss`, or null if it hasn't been read yet. */
    fun durationLabel(photo: Photo): String? {
        val ms = durations[photo.key] ?: return null
        val total = ms / 1000
        // Locale.US, not the default: a duration is a clock reading, and in a locale
        // using Eastern Arabic digits String.format would render one the grid can't
        // line up. ktlint's ImplicitDefaultLocale also flags the bare form.
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /**
     * Every image under DCIM and Pictures, newest first — and clips too when
     * [videos] is set. Off by default because the one other caller (the chat
     * background editor) picks a wallpaper and has no use for them.
     *
     * Off-main: this touches the filesystem. Returns empty rather than throwing when the permission
     * is missing — the caller is showing a prompt in that case anyway.
     */
    suspend fun scan(videos: Boolean = false): List<Photo> = withContext(Dispatchers.IO) {
        roots()
            .filter { it.isDirectory }
            .flatMap { root ->
                root.walkTopDown()
                    // Deep enough for DCIM/Camera and Pictures/Screenshots; not so
                    // deep that a stray folder of assets turns into a long walk.
                    .maxDepth(3)
                    // .thumbnails holds the launcher's own cached crops — junk here.
                    .onEnter { !it.name.startsWith(".") }
                    .mapNotNull { file ->
                        if (!file.isFile || file.length() <= 0L) return@mapNotNull null
                        // .trashed-* and .pending-* are MediaProvider's own
                        // bookkeeping and pass the extension filter otherwise.
                        if (file.name.startsWith(".")) return@mapNotNull null
                        when (file.extension.lowercase()) {
                            in IMAGE_EXTENSIONS -> Photo(file, file.lastModified(), Kind.IMAGE)
                            in VIDEO_EXTENSIONS ->
                                if (videos) Photo(file, file.lastModified(), Kind.VIDEO) else null
                            else -> null
                        }
                    }
                    .toList()
            }
            .sortedByDescending { it.takenAt }
            .take(MAX_ITEMS)
    }

    /** A downsampled thumbnail, or null if the file won't decode (a partially-written
     *  camera file, most likely). Cached in memory. Stills go through the EXIF-aware
     *  bitmap path; video gets a frame lifted out of the container instead. */
    suspend fun thumbnail(photo: Photo): ImageBitmap? {
        thumbnails.get(photo.key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val image = when (photo.kind) {
                Kind.IMAGE -> Attachments.decode(photo.file, THUMB_DIM)?.asImageBitmap()
                Kind.VIDEO -> videoFrame(photo)
            } ?: return@withContext null
            thumbnails.put(photo.key, image)
            image
        }
    }

    /**
     * A poster frame for a clip, plus its duration as a side effect.
     *
     * `MediaMetadataRetriever` and not `BitmapFactory` — a video file has no image
     * header, so `decode` returns null on one and the cell would sit blank forever.
     * The frame is taken at one second rather than zero: the first frame of a phone
     * recording is very often the black one the sensor produces before exposure
     * settles, and a grid of black squares is unpickable.
     *
     * Released in a `finally` — the retriever holds a codec, and this phone has few.
     */
    private fun videoFrame(photo: Photo): ImageBitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(photo.file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.let { durations[photo.key] = it }
            // OPTION_CLOSEST_SYNC, not OPTION_CLOSEST: the latter decodes forward from
            // the previous keyframe to land on the exact microsecond, which for a
            // long-GOP phone recording is real work per cell.
            val frame = retriever.getFrameAtTime(
                1_000_000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: retriever.frameAtTime // a clip shorter than a second has no frame there
            frame?.asImageBitmap()
        } catch (t: Throwable) {
            // A codec this phone doesn't have, or a file still being written. Neither is
            // worth a crash — the cell just stays empty and the clip is still sendable.
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * DCIM and Pictures. `getExternalStoragePublicDirectory` is deprecated in favour
     * of MediaStore, which is precisely the thing that doesn't work on this phone, so
     * the deprecation is noted and ignored.
     *
     * Movies is *not* walked. On this phone nothing writes there — the camera puts
     * clips in DCIM alongside stills — and adding it would mostly turn up other apps'
     * cached video.
     */
    @Suppress("DEPRECATION")
    private fun roots(): List<File> = listOf(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
    )
}
