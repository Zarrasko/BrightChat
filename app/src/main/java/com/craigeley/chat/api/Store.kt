package com.craigeley.chat.api

import android.content.Context
import com.craigeley.chat.Contacts
import org.json.JSONObject

/**
 * On-device state for the BlueBubbles client: just the server password, encrypted
 * at rest by [SecureStore]. The server URL is hardcoded — this app talks to
 * exactly one personal BlueBubbles Server, reached over Tailscale Serve (which
 * provides TLS + private routing; the server itself stays LAN-bound). Nothing
 * here touches Google Play Services.
 */
object Store {
    private const val PREFS = "chat"
    private const val KEY_PASSWORD = "bb_password" // encrypted
    private const val KEY_CONTACTS = "contacts"    // normalized key → name, JSON

    /** The single BlueBubbles Server this app talks to. */
    const val BASE_URL = "https://fieldmac-mini.tail311799.ts.net"

    fun password(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD, null)?.let { runCatching { SecureStore.decrypt(it) }.getOrNull() }

    fun setPassword(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PASSWORD, SecureStore.encrypt(value.trim())).apply()
    }

    fun hasPassword(context: Context): Boolean = !password(context).isNullOrBlank()

    fun clearPassword(context: Context) {
        prefs(context).edit().remove(KEY_PASSWORD).apply()
    }

    /**
     * Persists the contact index so the [com.craigeley.chat.socket.SocketService] —
     * which can run with no activity/ViewModel alive (e.g. started at boot) — can
     * resolve sender addresses to names for notifications. Stored as the already-
     * normalized key → name map ([Contacts.asMap]), no re-normalization on read.
     */
    fun setContacts(context: Context, byKey: Map<String, String>) {
        val obj = JSONObject()
        for ((k, v) in byKey) obj.put(k, v)
        prefs(context).edit().putString(KEY_CONTACTS, obj.toString()).apply()
    }

    /** The persisted contact index, or an empty one if none stored yet. */
    fun contacts(context: Context): Contacts {
        val json = prefs(context).getString(KEY_CONTACTS, null) ?: return Contacts()
        return runCatching {
            val obj = JSONObject(json)
            val map = HashMap<String, String>(obj.length())
            obj.keys().forEach { map[it] = obj.getString(it) }
            Contacts.fromMap(map)
        }.getOrDefault(Contacts())
    }

    /** Sign out: wipe the stored password. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
