package com.gios.lightchat

/**
 * An AI agent the user has added — a named chat that talks to one endpoint. Entirely
 * separate from the BlueBubbles/iMessage side of the app: agents have their own store
 * ([com.gios.lightchat.db.AgentStore]) and their own client
 * ([com.gios.lightchat.api.AgentApi]), and never touch the BlueBubbles server.
 *
 * [apiKey] is held in plaintext only in memory; [com.gios.lightchat.db.AgentStore]
 * encrypts it at rest with [com.gios.lightchat.api.SecureStore], the same key that
 * guards the BlueBubbles password.
 */
data class Agent(
    val id: String,
    val name: String,
    val provider: AgentProvider = AgentProvider.OPENAI_COMPATIBLE,
    val baseUrl: String,     // OPENAI_COMPATIBLE: no trailing slash (https://host or https://host/v1). ANTHROPIC: usually https://api.anthropic.com
    val apiKey: String,      // OPENAI_COMPATIBLE: bearer key. ANTHROPIC: x-api-key
    val model: String,       // model name to send (e.g. deepseek/deepseek-v4-pro, or claude-sonnet-5)
    val systemPrompt: String = "",
)

/**
 * Which API shape [com.gios.lightchat.api.AgentApi] speaks to reach this agent's endpoint.
 * Two provider families rather than one, because Anthropic's Messages API isn't
 * OpenAI-compatible: a top-level `system` field instead of a system message, a required
 * `max_tokens`, `x-api-key` instead of a bearer `Authorization` header, and a `content`
 * block array in the response instead of `choices[0].message`.
 */
enum class AgentProvider {
    /** `POST {baseUrl}/v1/chat/completions`, bearer auth — OpenRouter, OpenAI, LM Studio, June's Hermes server. */
    OPENAI_COMPATIBLE,

    /** `POST {baseUrl}/v1/messages`, `x-api-key` auth — Anthropic's own Claude API. */
    ANTHROPIC,
}

/** Who said a turn in an agent chat. */
enum class Role { USER, ASSISTANT }

/** One turn in an agent chat, oldest-first when listed. */
data class AgentMessage(
    val id: Long,            // autoincrement; 0 for a message not yet persisted
    val role: Role,
    val text: String,
    val date: Long,          // epoch millis
)
