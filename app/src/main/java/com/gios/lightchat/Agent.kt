package com.gios.lightchat

/**
 * An AI agent the user has added — a named chat that talks to one OpenAI-compatible
 * endpoint. Entirely separate from the BlueBubbles/iMessage side of the app: agents
 * have their own store ([com.gios.lightchat.db.AgentStore]) and their own client
 * ([com.gios.lightchat.api.AgentApi]), and never touch the BlueBubbles server.
 *
 * [apiKey] is held in plaintext only in memory; [com.gios.lightchat.db.AgentStore]
 * encrypts it at rest with [com.gios.lightchat.api.SecureStore], the same key that
 * guards the BlueBubbles password.
 */
data class Agent(
    val id: String,
    val name: String,
    val baseUrl: String,     // OpenAI-compatible base URL, no trailing slash (https://host or https://host/v1)
    val apiKey: String,      // bearer key
    val model: String,       // model name to send (e.g. deepseek/deepseek-v4-pro)
    val systemPrompt: String = "",
)

/** Who said a turn in an agent chat. */
enum class Role { USER, ASSISTANT }

/** One turn in an agent chat, oldest-first when listed. */
data class AgentMessage(
    val id: Long,            // autoincrement; 0 for a message not yet persisted
    val role: Role,
    val text: String,
    val date: Long,          // epoch millis
)
