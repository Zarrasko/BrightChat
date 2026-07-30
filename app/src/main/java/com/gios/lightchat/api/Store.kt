package com.gios.lightchat.api

import android.content.Context
import com.gios.lightchat.Contacts
import org.json.JSONObject

/**
 * On-device state for the BlueBubbles client: the server URL (entered at setup,
 * editable in Settings) and the server password, encrypted at rest by
 * [SecureStore]. The app talks to one self-hosted BlueBubbles Server — typically
 * reached over Tailscale Serve or another HTTPS reverse proxy (which provides TLS
 * + private routing; the server itself stays LAN-bound). Nothing here touches
 * Google Play Services.
 */
object Store {
    private const val PREFS = "chat"
    private const val KEY_PASSWORD = "bb_password" // encrypted
    private const val KEY_CONTACTS = "contacts"    // normalized key → name, JSON
    private const val KEY_BASE_URL = "base_url"    // the server URL, set at setup
    private const val KEY_PRIVATE_API = "private_api" // server's Private API live?
    private const val KEY_FAVORITES = "favorites"     // starred chat guids, newline-joined
    private const val KEY_ALERTED_AT = "alerted_at"   // newest message we've alerted for
    private const val KEY_POLL_AT = "poll_at"         // last catch-up attempt, wall clock
    private const val KEY_POLL_OK_AT = "poll_ok_at"   // last catch-up that reached the server
    private const val KEY_POLL_FAILS = "poll_fails"   // consecutive failures

    /** The configured BlueBubbles Server URL, or null if setup hasn't run yet. */
    fun baseUrl(context: Context): String? =
        prefs(context).getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() }

    /** Stores the server URL, normalizing it: default to https:// if no scheme is
     *  given, and drop a trailing slash so it concatenates cleanly with API paths. */
    fun setBaseUrl(context: Context, value: String) {
        var url = value.trim().trimEnd('/')
        if (url.isNotEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        prefs(context).edit().putString(KEY_BASE_URL, url).apply()
    }

    fun password(context: Context): String? =
        prefs(context).getString(KEY_PASSWORD, null)?.let { runCatching { SecureStore.decrypt(it) }.getOrNull() }

    fun setPassword(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PASSWORD, SecureStore.encrypt(value.trim())).apply()
    }

    fun hasPassword(context: Context): Boolean = !password(context).isNullOrBlank()

    /**
     * Persists the contact index so the [com.gios.lightchat.socket.SocketService] —
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

    /** Whether the server's Private API is live (tapbacks available). Cached from
     *  `server/info` so the UI knows on launch before the first refresh lands. */
    fun privateApi(context: Context): Boolean = prefs(context).getBoolean(KEY_PRIVATE_API, false)

    fun setPrivateApi(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PRIVATE_API, value).apply()
    }

    /**
     * The starred conversations, by primary chat guid. Persisted (not derived from
     * the server) because BlueBubbles exposes no favorites concept — this is a
     * local, per-phone pin. Stored newline-joined rather than as a JSON array
     * because guids never contain a newline and a StringSet would reorder.
     */
    fun favorites(context: Context): Set<String> =
        prefs(context).getString(KEY_FAVORITES, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    fun setFavorites(context: Context, guids: Set<String>) {
        prefs(context).edit().putString(KEY_FAVORITES, guids.joinToString("\n")).apply()
    }

    /**
     * The date of the newest message we've raised an alert for. The catch-up poll (see
     * `SocketService`) uses it as a watermark so a missed message notifies exactly once,
     * no matter how many times the poll runs or the process restarts. Persisted rather
     * than in-memory precisely because the process restarting is the case it exists for.
     */
    fun lastAlertedAt(context: Context): Long = prefs(context).getLong(KEY_ALERTED_AT, 0L)

    fun setLastAlertedAt(context: Context, value: Long) {
        prefs(context).edit().putLong(KEY_ALERTED_AT, value).apply()
    }

    /**
     * Delivery bookkeeping, written by every catch-up attempt.
     *
     * [lastPollAt] is what makes a *broken* alarm chain detectable. Each
     * `setAndAllowWhileIdle` firing arms the next one, so the chain is a single thread
     * that a force-stop, a lost firing, or an app update cuts for good — and nothing
     * about the app's state says so. Comparing this against the expected interval does
     * (`PollAlarm.looksStalled`), which is what lets a screen-on, a network coming back,
     * or the backup worker repair it.
     *
     * [lastPollOkAt] is separate because "the alarm fired" and "we heard from the server"
     * are different failures with the same symptom. [pollFailures] backs off the interval
     * so an unreachable server doesn't poll the battery flat, and is reset by any success.
     */
    fun lastPollAt(context: Context): Long = prefs(context).getLong(KEY_POLL_AT, 0L)

    fun lastPollOkAt(context: Context): Long = prefs(context).getLong(KEY_POLL_OK_AT, 0L)

    fun pollFailures(context: Context): Int = prefs(context).getInt(KEY_POLL_FAILS, 0)

    /** Records an attempt and its outcome in one write. */
    fun recordPoll(context: Context, ok: Boolean) {
        val now = System.currentTimeMillis()
        val edit = prefs(context).edit().putLong(KEY_POLL_AT, now)
        if (ok) {
            edit.putLong(KEY_POLL_OK_AT, now).putInt(KEY_POLL_FAILS, 0)
        } else {
            edit.putInt(KEY_POLL_FAILS, pollFailures(context) + 1)
        }
        edit.apply()
    }

    /** Sign out: wipe the stored password. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
