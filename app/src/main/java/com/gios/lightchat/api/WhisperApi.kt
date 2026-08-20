package com.gios.lightchat.api

import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * Turning a recording into words: `POST {baseUrl}/v1/audio/transcriptions`.
 *
 * OpenAI's Whisper endpoint, which is a *shape* rather than a vendor — `whisper.cpp`'s own server,
 * `faster-whisper-server`, LM Studio and OpenAI itself all answer the same multipart request, so the
 * same field in settings points at whichever of those you have. That is the same reasoning
 * [AgentApi] is built on, and the reason there is no model shipped in this app.
 *
 * ### Why not on the phone
 *
 * Whisper on-device means `whisper.cpp` through the NDK plus a model file. The smallest useful model
 * quantises to about thirty megabytes, which is most of an APK sideloaded onto a phone whose whole
 * premise is being small, and the tiny model is the one that mishears names. Against that, this
 * phone is already talking to a machine over Tailscale to get its messages at all — so the machine
 * that has the CPU does the transcribing, and the phone sends it a file.
 *
 * ### Multipart by hand
 *
 * `HttpURLConnection` and `org.json`, no networking dependency, same as every other client here.
 * Multipart is a boundary, a couple of headers and the bytes, and the file is **streamed** rather
 * than assembled in memory: a ten-minute recording is twenty-six megabytes, and building that as a
 * `ByteArray` to post it is how this would run the phone out of heap.
 */
class WhisperApi {

    /**
     * The transcript of [file], or throws [ApiException] if the server would not do it.
     *
     * [language] is a hint, not a demand — Whisper detects the language itself, and passing one
     * mainly stops it deciding that a quiet English recording is Welsh. Empty means let it decide.
     */
    fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        file: File,
        language: String = "",
    ): String {
        val boundary = "----brightchat" + System.nanoTime()
        val conn = (URL(url(baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            // Transcription is not fast. A minute of audio on a CPU-only server takes tens of
            // seconds, and the default read timeout would give up in the middle of the answer.
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            // Streamed, so the file never has to be held in memory to be counted.
            setChunkedStreamingMode(0)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }

        conn.outputStream.use { out ->
            out.field(boundary, "model", model.ifBlank { DEFAULT_MODEL })
            if (language.isNotBlank()) out.field(boundary, "language", language)
            // `text` rather than `json`: the only thing wanted here is the words, and asking for
            // json means parsing a document to reach the one field in it.
            out.field(boundary, "response_format", "text")
            out.filePart(boundary, "file", file)
            out.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (code !in 200..299) throw ApiException(code, reasonFrom(body, code))
        // `response_format=text` returns the transcript as the whole body. A server that ignores
        // the field and answers with json is handled rather than argued with.
        return if (body.trimStart().startsWith("{")) {
            runCatching { JSONObject(body).optString("text") }.getOrDefault(body).trim()
        } else {
            body.trim()
        }
    }

    /** An error worth showing a person, out of whatever the server said. */
    private fun reasonFrom(body: String, code: Int): String {
        val message = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()
        if (!message.isNullOrBlank()) return message
        return when (code) {
            401, 403 -> "The transcription server refused the key"
            404 -> "That server has no transcription endpoint"
            413 -> "That recording is too long for the server"
            else -> "The transcription server said $code"
        }
    }

    private fun OutputStream.field(boundary: String, name: String, value: String) {
        write(
            (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
                    value + "\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
    }

    private fun OutputStream.filePart(boundary: String, name: String, file: File) {
        write(
            (
                "--$boundary\r\n" +
                    "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n" +
                    "Content-Type: application/octet-stream\r\n\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        file.inputStream().use { it.copyTo(this, DEFAULT_BUFFER_SIZE) }
        write("\r\n".toByteArray(Charsets.UTF_8))
    }

    companion object {
        /** What OpenAI calls its Whisper, and what most compatible servers accept as an alias. */
        const val DEFAULT_MODEL = "whisper-1"

        private const val CONNECT_TIMEOUT_MS = 15_000

        /** Four minutes. A long recording on a CPU-only server genuinely takes this. */
        private const val READ_TIMEOUT_MS = 240_000

        /**
         * The endpoint, from whatever was typed into settings.
         *
         * A base URL with or without the version on it both work, because both are what people
         * paste: OpenAI's own documentation gives `https://api.openai.com/v1`, while a local
         * whisper server is usually just a host and a port.
         */
        fun url(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/v1")) "$base/audio/transcriptions"
            else "$base/v1/audio/transcriptions"
        }
    }
}
