package com.gios.lightchat.api

import org.json.JSONObject

/**
 * Reads an API key off a QR code, or null when the code doesn't carry one.
 *
 * The sibling of [parseWhisperQr] and generous for the same reason: the thing being moved is forty
 * to sixty characters of case-sensitive base62, and typing that on this phone's keyboard is a
 * five-minute job with a typo in it. So the code is almost never something this app produced — it
 * is `qrencode` over a key pasted from a dashboard on a laptop.
 *
 * Kept separate from [parseWhisperQr] rather than shared with it: that one reads a *server setup*
 * (a URL, a key and a model, any of which may be absent), and this reads one string. Folding them
 * together would mean a scan aimed at the GIF key being able to repoint the transcription server,
 * which is the sort of thing that is obvious in hindsight.
 *
 * Three shapes: a JSON object with a `key` field, a URL carrying `?key=`, or a single bare token.
 * A blob with whitespace in it is rejected — that is somebody's note, not a key, and storing it
 * would fail every request afterwards with nothing to explain why.
 */
fun parseApiKeyQr(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    if (text.startsWith("{")) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return sequenceOf("key", "api_key", "apiKey", "apikey", "token")
            .map { obj.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
    }

    if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
        val query = text.substringAfter('?', "")
        if (query.isBlank()) return null
        return query.split('&')
            .mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) null else pair.substring(0, eq).lowercase() to pair.substring(eq + 1)
            }
            .firstOrNull { it.first in setOf("key", "api_key", "apikey", "token") }
            ?.second
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.takeIf { it.isNotBlank() }
    }

    if (text.any { it.isWhitespace() }) return null
    return text
}
