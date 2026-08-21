package com.gios.lightchat

import com.gios.lightchat.api.parseWhisperQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanner exists so an API key never has to be typed on this keyboard, which means the
 * codes it has to read are the ones people already make — `qrencode` over a pasted key — not a
 * format this app invented. These are the shapes that must work.
 */
class WhisperQrTest {

    @Test
    fun `a bare key is a key`() {
        val config = parseWhisperQr("sk-proj-AbCdEf0123456789")
        assertEquals("sk-proj-AbCdEf0123456789", config?.key)
        // Nothing was said about the server, so nothing is claimed about it.
        assertNull(config?.url)
        assertNull(config?.model)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        // qrencode with a trailing newline, which is what a shell pipeline gives you.
        assertEquals("sk-abc", parseWhisperQr("  sk-abc\n")?.key)
    }

    @Test
    fun `a sentence is not a key`() {
        // Scanning a poster or a wifi code should say "that isn't a key", not store the words
        // and fail every request afterwards with no clue why.
        assertNull(parseWhisperQr("hello there"))
        assertNull(parseWhisperQr("   "))
    }

    @Test
    fun `json carries the whole setup`() {
        val config = parseWhisperQr(
            """{"url":"https://box:9000","key":"sk-1","model":"large-v3"}""",
        )
        assertEquals("https://box:9000", config?.url)
        assertEquals("sk-1", config?.key)
        assertEquals("large-v3", config?.model)
    }

    @Test
    fun `json accepts the agent QR spellings`() {
        // Same field names the agent scanner takes, so one generator can make both.
        val config = parseWhisperQr("""{"base_url":"https://box","api_key":"sk-2"}""")
        assertEquals("https://box", config?.url)
        assertEquals("sk-2", config?.key)
        val camel = parseWhisperQr("""{"baseUrl":"https://box","apiKey":"sk-3"}""")
        assertEquals("https://box", camel?.url)
        assertEquals("sk-3", camel?.key)
    }

    @Test
    fun `json with nothing useful is rejected`() {
        assertNull(parseWhisperQr("""{"type":"something-else"}"""))
    }

    @Test
    fun `a url is the server`() {
        val config = parseWhisperQr("https://whisper.example:9000")
        assertEquals("https://whisper.example:9000", config?.url)
        assertNull(config?.key)
    }

    @Test
    fun `a url can carry the key and the model`() {
        val config = parseWhisperQr("https://box:9000/?api_key=sk-4&model=large-v3")
        // The query is stripped: WhisperApi appends its own path to whatever it is given, and a
        // base URL ending in ?api_key= would build a nonsense request.
        assertEquals("https://box:9000/", config?.url)
        assertEquals("sk-4", config?.key)
        assertEquals("large-v3", config?.model)
    }

    @Test
    fun `query values are percent-decoded`() {
        assertEquals("a b/c+d", parseWhisperQr("https://box?key=a%20b%2Fc%2Bd")?.key)
    }
}
