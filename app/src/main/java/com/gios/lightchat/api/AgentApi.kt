package com.gios.lightchat.api

import com.gios.lightchat.Agent
import com.gios.lightchat.AgentMessage
import com.gios.lightchat.AgentProvider
import com.gios.lightchat.Role
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * A minimal chat client — plain [HttpURLConnection] + `org.json`, no networking
 * dependency, same as [BlueBubblesApi] — speaking whichever of two request/response
 * shapes [Agent.provider] calls for. Sends the agent's system prompt plus the full
 * conversation history, and reads back the assistant's reply text.
 *
 * [AgentProvider.OPENAI_COMPATIBLE] is `POST {baseUrl}/v1/chat/completions` with a
 * bearer key. June's Hermes API server (`june-api.gzl.dev`) is just one such endpoint;
 * any OpenAI-compatible server (OpenRouter, OpenAI, a local LM Studio box) works the
 * same way, which is the point of the agent being user-defined.
 *
 * [AgentProvider.ANTHROPIC] is `POST {baseUrl}/v1/messages` with an `x-api-key`
 * header — Anthropic's own Messages API, which isn't OpenAI-shaped: the system
 * prompt is a top-level field rather than a message, `max_tokens` is required, and
 * the reply comes back as a `content` block array rather than `choices[0].message`.
 */
class AgentApi {

    /**
     * Sends [history] (oldest-first) to [agent]'s endpoint and returns the assistant's
     * reply text. Throws [ApiException] on a non-2xx response, so the caller can
     * distinguish auth/network failures.
     */
    fun complete(agent: Agent, history: List<AgentMessage>): String = when (agent.provider) {
        AgentProvider.OPENAI_COMPATIBLE -> completeOpenAi(agent, history)
        AgentProvider.ANTHROPIC -> completeAnthropic(agent, history)
    }

    private fun completeOpenAi(agent: Agent, history: List<AgentMessage>): String {
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

        val text = post(
            url = openAiChatUrl(agent.baseUrl),
            body = body,
            headers = mapOf("Authorization" to "Bearer ${agent.apiKey}"),
        )
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return ""
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        return choice?.optJSONObject("message")?.optString("content").orEmpty()
    }

    private fun completeAnthropic(agent: Agent, history: List<AgentMessage>): String {
        val messages = JSONArray()
        for (m in history) {
            messages.put(
                JSONObject()
                    .put("role", if (m.role == Role.USER) "user" else "assistant")
                    .put("content", m.text),
            )
        }
        val body = JSONObject()
            .put("model", agent.model)
            .put("max_tokens", ANTHROPIC_MAX_TOKENS)
            .put("messages", messages)
        if (agent.systemPrompt.isNotBlank()) body.put("system", agent.systemPrompt)

        val base = agent.baseUrl.trim().trimEnd('/').ifBlank { ANTHROPIC_DEFAULT_BASE_URL }
        val text = post(
            url = "$base/v1/messages",
            body = body,
            headers = mapOf(
                "x-api-key" to agent.apiKey,
                "anthropic-version" to ANTHROPIC_VERSION,
            ),
        )
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return ""
        val blocks = root.optJSONArray("content") ?: return ""
        // Concatenate every text block rather than just the first — a reply can come
        // back as more than one, and dropping the rest would silently truncate it.
        return (0 until blocks.length())
            .mapNotNull { blocks.optJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
    }

    /** POSTs JSON, throws [ApiException] on a non-2xx response, returns the raw body text. */
    private fun post(url: String, body: JSONObject, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            // A model turn can take a while; be generous.
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        conn.disconnect()
        if (code !in 200..299) throw ApiException(code, text.take(400))
        return text
    }

    /** The full completion URL: `{base}/v1/chat/completions`, tolerating a trailing `/v1`. */
    private fun openAiChatUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/v1")) "$base/chat/completions" else "$base/v1/chat/completions"
    }

    companion object {
        /** Prefilled into the editor when Anthropic is picked; still user-editable for a proxy. */
        const val ANTHROPIC_DEFAULT_BASE_URL = "https://api.anthropic.com"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000

        // Anthropic requires a cap per request; there's no "unbounded" option. Generous
        // enough that a real reply is never cut off mid-thought.
        private const val ANTHROPIC_MAX_TOKENS = 4096
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
