package com.gios.lightchat

import com.gios.lightchat.api.parseApiKeyQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a scanned code is allowed to mean when what is wanted is one API key. */
class KeyQrTest {

    @Test
    fun `a bare token is the key — the qrencode case`() {
        assertEquals("kl_live_9aF2xQ", parseApiKeyQr("kl_live_9aF2xQ"))
        assertEquals("kl_live_9aF2xQ", parseApiKeyQr("  kl_live_9aF2xQ \n"))
    }

    @Test
    fun `json says which field it is`() {
        assertEquals("abc123", parseApiKeyQr("""{"key":"abc123"}"""))
        assertEquals("abc123", parseApiKeyQr("""{"api_key":"abc123"}"""))
        assertEquals("abc123", parseApiKeyQr("""{"token":"abc123"}"""))
        assertNull(parseApiKeyQr("""{"url":"https://example.test"}"""))
        assertNull(parseApiKeyQr("""{"key":"" }"""))
    }

    @Test
    fun `a url carries it in the query, percent-decoded`() {
        assertEquals("abc/123", parseApiKeyQr("https://klipy.com/panel?key=abc%2F123"))
        assertEquals("xyz", parseApiKeyQr("https://klipy.com/panel?other=1&api_key=xyz"))
        assertNull(parseApiKeyQr("https://klipy.com/panel"))
    }

    @Test
    fun `a note somebody wrote is not a key`() {
        assertNull(parseApiKeyQr("my klipy key is abc123"))
        assertNull(parseApiKeyQr(""))
        assertNull(parseApiKeyQr("   "))
    }
}
