package com.gios.lightchat

import java.io.File

/**
 * What kind of file an attachment is, and what to call it on the wire.
 *
 * One table, in one place, because the same mapping is needed at both ends of a file's journey
 * through this app and the two used to disagree. A share arrives as a URI with a mime type and is
 * copied into the cache under a *filename*; the send then reads the mime back off that filename. So
 * mime-to-extension and extension-to-mime have to be inverses of each other, and when they were not
 * the symptom was a WAV cached as `.x-wav`, sent as `image/jpeg`, and unplayable at the other end.
 *
 * Free of Android on purpose, so the table has a test rather than a phone.
 */
object MediaKind {

    private val VIDEO = setOf("mov", "mp4", "m4v", "3gp")

    /** BrightRecorder shares WAVs in; an iPhone sends `.caf` and `.m4a`. */
    private val AUDIO = setOf("wav", "m4a", "mp3", "aac", "caf", "aiff", "aif", "ogg", "opus", "flac")

    fun isVideo(file: File): Boolean = file.extension.lowercase() in VIDEO

    fun isAudio(file: File): Boolean = file.extension.lowercase() in AUDIO

    /**
     * Whether this file is streamed off disk rather than read into memory and sent as bytes.
     *
     * The distinction that matters on the send path is not video-versus-photo, it is *inline versus
     * not*: a photo's bytes are read anyway to seed the optimistic bubble, and everything else is
     * handed to the API as a `File` so a long recording never enters the heap.
     */
    fun isStreamed(file: File): Boolean = isVideo(file) || isAudio(file)

    /** "audio clip" or "video", for a message about one. */
    fun label(file: File): String = if (isAudio(file)) "audio clip" else "video"

    /**
     * The mime type to send [extension] as.
     *
     * `video/quicktime` for `.mov` and not `video/mp4`, even though the two containers are near
     * enough the same thing: it is what an iPhone sends and what Messages on the other end expects
     * to be handed back, and the mime is what the receiving client branches on. `audio/x-wav` and
     * `audio/mp4` are the same story for sound.
     *
     * Falls back to `image/jpeg`, because a still is what almost everything reaching this app is and
     * a wrong image type is the least harmful thing to guess.
     */
    fun mimeOf(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        "mov" -> "video/quicktime"
        "mp4", "m4v" -> "video/mp4"
        "3gp" -> "video/3gpp"
        "wav" -> "audio/x-wav"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "caf" -> "audio/x-caf"
        "aiff", "aif" -> "audio/aiff"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "image/jpeg"
    }

    /**
     * The extension to cache a file of [mime] under, so that [mimeOf] gives the mime back.
     *
     * A mime subtype is not an extension and cannot be used as one — `audio/x-wav` would produce a
     * file called `.x-wav`, which [mimeOf] does not know and would send as a photograph. Only the
     * types this app can actually send are named; anything else keeps the old behaviour of guessing
     * a still, which is what the send does with it anyway.
     */
    fun extensionOf(mime: String?): String {
        val type = mime?.trim()?.lowercase().orEmpty()
        val subtype = type.substringAfterLast('/', "")
        return when {
            type.startsWith("audio/") -> when (subtype) {
                "x-wav", "wave", "wav", "vnd.wave" -> "wav"
                "mpeg", "mp3" -> "mp3"
                "mp4", "m4a", "x-m4a" -> "m4a"
                "x-caf", "caf" -> "caf"
                "aiff", "x-aiff" -> "aiff"
                "ogg", "opus" -> "ogg"
                "aac" -> "aac"
                "flac", "x-flac" -> "flac"
                else -> "m4a"
            }
            type.startsWith("video/") -> when (subtype) {
                "quicktime" -> "mov"
                "3gpp" -> "3gp"
                "mp4", "m4v" -> "mp4"
                else -> "mp4"
            }
            subtype == "jpeg" || subtype == "jpg" -> "jpg"
            subtype.isNotBlank() && subtype.length <= 5 && subtype.all { it.isLetterOrDigit() } -> subtype
            else -> "jpg"
        }
    }
}
