package com.gios.lightchat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file-type table, and the one property that actually matters about it.
 *
 * A share arrives as a URI with a mime type and is cached under a *filename*; the send then reads
 * the mime back off that filename. So the two directions have to be inverses, and when they were not
 * the symptom was a WAV cached as `.x-wav`, sent as `image/jpeg`, and unplayable at the other end.
 */
class MediaKindTest {

    private fun f(name: String) = File("/tmp/$name")

    // ------------------------------------------------------------------ the round trip

    /** The property the whole table exists to have. */
    @Test
    fun `caching a shared file and sending it again preserves its type`() {
        val mimes = listOf(
            "audio/x-wav", "audio/wav", "audio/mpeg", "audio/mp4", "audio/x-caf",
            "audio/aiff", "audio/ogg", "audio/aac", "audio/flac",
            "video/quicktime", "video/mp4", "video/3gpp",
            "image/png", "image/gif", "image/webp", "image/heic", "image/jpeg",
        )
        mimes.forEach { mime ->
            val extension = MediaKind.extensionOf(mime)
            val back = MediaKind.mimeOf(extension)
            assertEquals(
                "$mime cached as .$extension came back as $back",
                mime.substringBefore('/'),
                back.substringBefore('/'),
            )
        }
    }

    /** The specific case that was broken: a moment shared out of BrightRecorder. */
    @Test
    fun `a wav shared in is still a wav going out`() {
        assertEquals("wav", MediaKind.extensionOf("audio/x-wav"))
        assertEquals("audio/x-wav", MediaKind.mimeOf("wav"))
    }

    /** A mime subtype is not an extension, and using one as an extension was the bug. */
    @Test
    fun `a subtype that is not an extension is translated, not used`() {
        assertEquals("wav", MediaKind.extensionOf("audio/x-wav"))
        assertEquals("mp3", MediaKind.extensionOf("audio/mpeg"))
        assertEquals("mov", MediaKind.extensionOf("video/quicktime"))
        assertEquals("3gp", MediaKind.extensionOf("video/3gpp"))
    }

    /** `audio/mp4` and `video/mp4` share a subtype and are not the same thing. */
    @Test
    fun `an mp4 audio is not cached as an mp4 video`() {
        assertEquals("m4a", MediaKind.extensionOf("audio/mp4"))
        assertEquals("mp4", MediaKind.extensionOf("video/mp4"))
        assertTrue(MediaKind.isAudio(f("x.m4a")))
        assertTrue(MediaKind.isVideo(f("x.mp4")))
    }

    @Test
    fun `an unknown audio type still lands on something playable`() {
        assertTrue(MediaKind.isAudio(f("x." + MediaKind.extensionOf("audio/weird-new-codec"))))
    }

    @Test
    fun `nothing and nonsense fall back to a still`() {
        assertEquals("jpg", MediaKind.extensionOf(null))
        assertEquals("jpg", MediaKind.extensionOf(""))
        assertEquals("jpg", MediaKind.extensionOf("application/octet-stream"))
    }

    // -------------------------------------------------------------------- the kinds

    @Test
    fun `sounds are audio and clips are video`() {
        listOf("wav", "m4a", "mp3", "aac", "caf", "aiff", "ogg", "opus", "flac").forEach {
            assertTrue("$it should be audio", MediaKind.isAudio(f("a.$it")))
            assertFalse("$it should not be video", MediaKind.isVideo(f("a.$it")))
        }
        listOf("mov", "mp4", "m4v", "3gp").forEach {
            assertTrue("$it should be video", MediaKind.isVideo(f("a.$it")))
            assertFalse("$it should not be audio", MediaKind.isAudio(f("a.$it")))
        }
    }

    @Test
    fun `a still is neither, and is not streamed`() {
        listOf("jpg", "png", "heic", "gif").forEach {
            assertFalse(MediaKind.isAudio(f("a.$it")))
            assertFalse(MediaKind.isVideo(f("a.$it")))
            assertFalse("a still is read into memory, not streamed", MediaKind.isStreamed(f("a.$it")))
        }
    }

    /** Both sounds and clips go down the streaming path; only stills are read into the heap. */
    @Test
    fun `sounds and clips are both streamed`() {
        assertTrue(MediaKind.isStreamed(f("a.wav")))
        assertTrue(MediaKind.isStreamed(f("a.mov")))
    }

    @Test
    fun `the extension is read without regard to case`() {
        assertTrue(MediaKind.isAudio(f("A.WAV")))
        assertEquals("audio/x-wav", MediaKind.mimeOf("WAV"))
    }

    @Test
    fun `a message about a file names the right kind of thing`() {
        assertEquals("audio clip", MediaKind.label(f("a.wav")))
        assertEquals("video", MediaKind.label(f("a.mov")))
    }
}
