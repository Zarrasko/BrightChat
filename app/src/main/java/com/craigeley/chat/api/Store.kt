package com.craigeley.chat.api

import android.content.Context

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

    /** Sign out: wipe the stored password. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
