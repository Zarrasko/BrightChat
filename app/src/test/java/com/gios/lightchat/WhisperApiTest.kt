package com.gios.lightchat

import com.gios.lightchat.api.WhisperApi
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the transcription request is sent.
 *
 * A small thing to test and the one worth testing, because it is the thing people get wrong when
 * they paste a URL: OpenAI's own documentation gives an endpoint that already ends in `/v1`, while a
 * local whisper server is usually a bare host and port. Both are what somebody will type, and
 * doubling the version segment is a 404 that reads as "transcription is broken".
 */
class WhisperApiTest {

    @Test
    fun `a bare host gets the version and the path`() {
        assertEquals(
            "https://box.local:9000/v1/audio/transcriptions",
            WhisperApi.url("https://box.local:9000"),
        )
    }

    /** The case that would otherwise become `/v1/v1/audio/transcriptions`. */
    @Test
    fun `a url that already names the version is not given a second one`() {
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            WhisperApi.url("https://api.openai.com/v1"),
        )
    }

    @Test
    fun `a trailing slash is not a path segment`() {
        assertEquals(
            "https://box.local/v1/audio/transcriptions",
            WhisperApi.url("https://box.local/"),
        )
        assertEquals(
            "https://api.openai.com/v1/audio/transcriptions",
            WhisperApi.url("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun `whitespace around a pasted url is ignored`() {
        assertEquals(
            "https://box.local/v1/audio/transcriptions",
            WhisperApi.url("  https://box.local  "),
        )
    }

    /** A host with a path of its own — a reverse proxy — keeps it. */
    @Test
    fun `a url behind a path keeps the path`() {
        assertEquals(
            "https://box.local/whisper/v1/audio/transcriptions",
            WhisperApi.url("https://box.local/whisper"),
        )
    }

    @Test
    fun `the default model is the one every compatible server accepts`() {
        assertEquals("whisper-1", WhisperApi.DEFAULT_MODEL)
    }
}
