package com.gios.lightchat.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.gios.lightchat.Agent
import com.gios.lightchat.AgentMessage
import com.gios.lightchat.Role
import com.gios.lightchat.api.SecureStore

/**
 * On-device store for agents and their conversations — a *separate* database from
 * [MessageStore], because agents are a separate system from iMessage and must not
 * share its cache semantics (which throws the whole thing away and re-syncs on a
 * schema bump). Raw SQLite, no Room, same reasoning as [MessageStore].
 *
 * The API key is encrypted at rest with [SecureStore] (AES-256-GCM in the
 * AndroidKeyStore) so it never sits in plaintext in the database.
 */
class AgentStore private constructor(context: Context) {

    private val helper = Helper(context.applicationContext)

    private class Helper(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE agents (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    api_key TEXT NOT NULL,
                    model TEXT NOT NULL,
                    system_prompt TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE agent_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    agent_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    text TEXT NOT NULL,
                    date INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX idx_agent_messages ON agent_messages (agent_id, date DESC)")
        }

        override fun onDowngrade(db: SQLiteDatabase, old: Int, new: Int) = onUpgrade(db, old, new)

        override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
            db.execSQL("DROP TABLE IF EXISTS agents")
            db.execSQL("DROP TABLE IF EXISTS agent_messages")
            onCreate(db)
        }
    }

    /* ---------------- agents ---------------- */

    fun agents(): List<Agent> {
        val out = ArrayList<Agent>()
        helper.readableDatabase
            .query("agents", null, null, null, null, null, "name COLLATE NOCASE")
            .use { c ->
                val id = c.getColumnIndexOrThrow("id")
                val name = c.getColumnIndexOrThrow("name")
                val url = c.getColumnIndexOrThrow("base_url")
                val key = c.getColumnIndexOrThrow("api_key")
                val model = c.getColumnIndexOrThrow("model")
                val sys = c.getColumnIndexOrThrow("system_prompt")
                while (c.moveToNext()) {
                    out.add(
                        Agent(
                            id = c.getString(id),
                            name = c.getString(name),
                            baseUrl = c.getString(url),
                            apiKey = decrypt(c.getString(key)),
                            model = c.getString(model),
                            systemPrompt = c.getString(sys),
                        ),
                    )
                }
            }
        return out
    }

    fun agent(id: String): Agent? {
        helper.readableDatabase
            .query("agents", null, "id = ?", arrayOf(id), null, null, null, "1")
            .use { c ->
                if (!c.moveToFirst()) return null
                val name = c.getColumnIndexOrThrow("name")
                val url = c.getColumnIndexOrThrow("base_url")
                val key = c.getColumnIndexOrThrow("api_key")
                val model = c.getColumnIndexOrThrow("model")
                val sys = c.getColumnIndexOrThrow("system_prompt")
                return Agent(
                    id = id,
                    name = c.getString(name),
                    baseUrl = c.getString(url),
                    apiKey = decrypt(c.getString(key)),
                    model = c.getString(model),
                    systemPrompt = c.getString(sys),
                )
            }
    }

    fun putAgent(agent: Agent) {
        val values = ContentValues(6).apply {
            put("id", agent.id)
            put("name", agent.name)
            put("base_url", agent.baseUrl)
            put("api_key", encrypt(agent.apiKey))
            put("model", agent.model)
            put("system_prompt", agent.systemPrompt)
        }
        helper.writableDatabase.insertWithOnConflict("agents", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Deletes the agent and its whole conversation. */
    fun deleteAgent(id: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("agents", "id = ?", arrayOf(id))
            db.delete("agent_messages", "agent_id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /* ---------------- messages ---------------- */

    /** A chat's newest [limit] messages, oldest-first (the order the thread wants). */
    fun messages(agentId: String, limit: Int = PAGE, before: Long? = null): List<AgentMessage> {
        val db = helper.readableDatabase
        val where = if (before == null) "agent_id = ?" else "agent_id = ? AND date < ?"
        val args = if (before == null) arrayOf(agentId) else arrayOf(agentId, before.toString())
        val out = ArrayList<AgentMessage>(limit)
        db.query("agent_messages", null, where, args, null, null, "date DESC", limit.toString())
            .use { c ->
                val id = c.getColumnIndexOrThrow("id")
                val role = c.getColumnIndexOrThrow("role")
                val text = c.getColumnIndexOrThrow("text")
                val date = c.getColumnIndexOrThrow("date")
                while (c.moveToNext()) {
                    out.add(
                        AgentMessage(
                            id = c.getLong(id),
                            role = if (c.getString(role) == "USER") Role.USER else Role.ASSISTANT,
                            text = c.getString(text),
                            date = c.getLong(date),
                        ),
                    )
                }
            }
        return out.asReversed()
    }

    /** Persists one turn and returns its id. */
    fun addMessage(agentId: String, role: Role, text: String, date: Long): Long {
        val values = ContentValues(4).apply {
            put("agent_id", agentId)
            put("role", if (role == Role.USER) "USER" else "ASSISTANT")
            put("text", text)
            put("date", date)
        }
        return helper.writableDatabase.insertOrThrow("agent_messages", null, values)
    }

    /** The newest turn in an agent's chat, or null — the conversation-list preview. */
    fun lastMessage(agentId: String): AgentMessage? {
        helper.readableDatabase
            .query("agent_messages", null, "agent_id = ?", arrayOf(agentId), null, null, "date DESC", "1")
            .use { c ->
                if (!c.moveToFirst()) return null
                val id = c.getColumnIndexOrThrow("id")
                val role = c.getColumnIndexOrThrow("role")
                val text = c.getColumnIndexOrThrow("text")
                val date = c.getColumnIndexOrThrow("date")
                return AgentMessage(
                    id = c.getLong(id),
                    role = if (c.getString(role) == "USER") Role.USER else Role.ASSISTANT,
                    text = c.getString(text),
                    date = c.getLong(date),
                )
            }
    }

    /** First line of an agent's latest turn, for the list preview (the list adds the
     *  "You: " prefix itself from the message's role). */
    fun preview(agentId: String): String? {
        val last = lastMessage(agentId) ?: return null
        return last.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun encrypt(plain: String): String =
        if (plain.isBlank()) "" else SecureStore.encrypt(plain)

    private fun decrypt(blob: String): String =
        if (blob.isBlank()) "" else runCatching { SecureStore.decrypt(blob) }.getOrDefault("")

    companion object {
        @Volatile
        private var instance: AgentStore? = null

        fun get(context: Context): AgentStore =
            instance ?: synchronized(this) {
                instance ?: AgentStore(context.applicationContext).also { instance = it }
            }

        private const val NAME = "lightchat_agents.db"
        private const val VERSION = 1

        /** Messages per thread page. */
        const val PAGE = 50
    }
}
