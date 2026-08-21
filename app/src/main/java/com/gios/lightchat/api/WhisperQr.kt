package com.gios.lightchat.api

import org.json.JSONObject

/**
 * What a scanned QR code says about a transcription server.
 *
 * Every field is optional, and a null one means "the code didn't mention this" — not "clear it".
 * A code carrying only a key is the common case and must leave a working URL alone.
 */
data class WhisperConfig(
    val url: String? = null,
    val key: String? = null,
    val model: String? = null,
)

/**
 * Reads a QR code into a [WhisperConfig], or null when it says nothing about transcription.
 *
 * ### Why this is generous about shape
 *
 * The thing being moved is an API key: forty to sixty characters of case-sensitive base62 that
 * nobody should be typing on a phone keyboard, least of all this one. So the code being scanned
 * is almost never something this app produced — it is whatever the person made on their laptop,
 * usually `qrencode` over the key they just pasted from a dashboard. Rejecting that in favour of
 * a house JSON format would mean the feature only works for people who already had the key on
 * the phone.
 *
 * Three shapes, in order of how much they say:
 *
 *  - **A JSON object.** `{"url": …, "key": …, "model": …}`, with `base_url`/`baseUrl` and
 *    `api_key`/`apiKey` accepted for the same fields, matching the agent QR format. The whole
 *    setup in one code.
 *  - **A URL.** Taken as the server, with `?key=`/`?api_key=`/`?model=` read off the query and
 *    stripped, so a code made by a self-hosting setup script can carry both.
 *  - **Anything else on one line.** Taken as the key. This is the `qrencode` case.
 *
 * Whitespace inside is what separates "a key" from "a note someone wrote", so a multi-word blob
 * is rejected rather than stored as a key that will fail every request with no explanation.
 */
fun parseWhisperQr(raw: String): WhisperConfig? {
    val text = raw.trim()
    if (text.isEmpty()) return null

    json(text)?.let { root ->
        val config = WhisperConfig(
            url = root.pick("url", "base_url", "baseUrl"),
            key = root.pick("key", "api_key", "apiKey"),
            model = root.pick("model"),
        )
        return config.takeIf { it != WhisperConfig() }
    }

    if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) {
        return fromUrl(text)
    }

    // A key: one token, no spaces. Length is not checked — keys differ per provider, and a
    // too-short one fails loudly at the server, which is a better error than this one guessing.
    if (text.any { it.isWhitespace() }) return null
    return WhisperConfig(key = text)
}

private fun json(text: String): JSONObject? =
    if (text.startsWith("{")) runCatching { JSONObject(text) }.getOrNull() else null

private fun JSONObject.pick(vararg names: String): String? =
    names.asSequence()
        .map { optString(it) }
        .firstOrNull { it.isNotBlank() }

/**
 * Splits a scanned URL into the server and whatever it carried in its query.
 *
 * The query is stripped from the stored URL either way: [WhisperApi] appends its own path, and a
 * base URL with a dangling `?key=` would produce a nonsense request.
 */
private fun fromUrl(text: String): WhisperConfig {
    val cut = text.indexOf('?')
    if (cut < 0) return WhisperConfig(url = text)
    val base = text.substring(0, cut).trimEnd('&')
    val params = text.substring(cut + 1)
        .split('&')
        .mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0) null else pair.substring(0, eq).lowercase() to decode(pair.substring(eq + 1))
        }
        .toMap()
    return WhisperConfig(
        url = base.ifBlank { null },
        key = params["key"] ?: params["api_key"] ?: params["apikey"],
        model = params["model"],
    )
}

/** Percent-decoding, and `+` for a space, for query values. Bad escapes are left as typed. */
private fun decode(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
