package com.gios.lightchat.api

import com.gios.lightchat.Agent
import com.gios.lightchat.AgentMessage
import com.gios.lightchat.Role
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * A minimal OpenAI-compatible chat client — plain [HttpURLConnection] + `org.json`,
 * no networking dependency, same as [BlueBubblesApi]. One endpoint:
 * `POST {baseUrl}/v1/chat/completions` with a bearer key, sending the agent's
 * system prompt plus the full conversation history, and reading back the first
 * assistant completion.
 *
 * June's Hermes API server (`june-api.gzl.dev`) is just one such endpoint; any
 * OpenAI-compatible server (OpenRouter, OpenAI, a local LM Studio box) works the
 * same way, which is the point of the agent being user-defined.
 */
class AgentApi {

    /**
     * Sends [history] (oldest-first) to [agent]'s endpoint and returns the assistant's
     * reply text. Throws [ApiException] on a non-2xx response, so the caller can
     * distinguish auth/network failures.
     */
    fun complete(agent: Agent, history: List<AgentMessage>): String {
        val messages = JSONArray()
        if (agent.systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", agent.systemPrompt))
        }
        for (m in history) {
            messages.put(
                JSONObject()
                    .put("role", if (m.role == Role.USER) "user" else "assistant")
                    .put("content", m.text),
            )
        }
        val body = JSONObject()
            .put("model", agent.model)
            .put("messages", messages)
            .put("stream", false)

        val conn = (URL(chatUrl(agent.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            // A model turn can take a while; be generous.
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${agent.apiKey}")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw ApiException(code, text.take(400))
        return parseContent(text)
    }

    /** The full completion URL: `{base}/v1/chat/completions`, tolerating a trailing `/v1`. */
    private fun chatUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
    }

    private fun parseContent(text: String): String {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return ""
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        return choice?.optJSONObject("message")?.optString("content").orEmpty()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 120_000
    }
}
