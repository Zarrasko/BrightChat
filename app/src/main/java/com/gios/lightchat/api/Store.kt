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
    private const val KEY_NOTIFY_UNKNOWN = "notify_unknown" // alert for senders not in the address book
    private const val KEY_NOTED = "noted_keys"        // conversations whose note has been opened
    private const val KEY_PINS = "pinned_guids"      // starred chats held at the top, newest pin first
    private const val KEY_SPEED_DIAL = "speed_dial"   // digit -> number\u0000name
    private const val KEY_CODE = "login_code"         // the newest one-time code seen
    private const val KEY_CODE_AT = "login_code_at"   // when the message carrying it arrived

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

    /**
     * Whether a message from somebody not in the address book should raise an alert.
     *
     * **Off by default**, which is the whole point of it. An iMessage account that has existed for
     * years receives a steady trickle from short codes, delivery services, two-factor senders and
     * whoever last had your number — and on this phone every one of those buzzes, lights the panel
     * and puts a box in front of whatever you were doing. The messages still arrive and still show
     * an unread mark in the list; they just don't interrupt.
     *
     * "Known" is [Contacts.knows]: a named group, or any participant in the address book. Same
     * definition as the list's Known tab, so what the setting does is exactly "alert me about the
     * Known and Favourites tabs" — no second notion of who counts as a stranger.
     */
    fun notifyUnknown(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_UNKNOWN, false)

    fun setNotifyUnknown(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_UNKNOWN, value).apply()
    }

    /**
     * Whether this phone has ever opened the LightNotebook note for a conversation.
     *
     * The contact page's note row is a deep link and nothing more — LightNotebook is not
     * queried, so there is no way to ask whether a note exists. This is the honest half of
     * the answer: not "there is a note", but "you have been here before", which is what
     * decides between "Open note" and "Add a note".
     *
     * Keyed by the conversation's normalised handles (see `ChatViewModel.noteKey`), which
     * never contain a newline, so the same newline-joined storage as [favorites] holds.
     */
    fun noteOpened(context: Context, key: String): Boolean = key in notedKeys(context)

    fun setNoteOpened(context: Context, key: String) {
        if (key.isBlank()) return
        val next = notedKeys(context) + key
        prefs(context).edit().putString(KEY_NOTED, next.joinToString("\n")).apply()
    }

    private fun notedKeys(context: Context): Set<String> =
        prefs(context).getString(KEY_NOTED, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()

    /**
     * How long a one-time code is worth offering.
     *
     * Three minutes, which is shorter than any service's own expiry and longer than the walk
     * from "a code arrived" to "the field is focused". The number is a guess at human latency
     * rather than at cryptography: the cost of being too short is retyping six digits, and the
     * cost of being too long is the keyboard confidently offering a code that no longer works,
     * in the one slot the user taps without reading.
     */
    const val CODE_TTL_MS = 3 * 60 * 1000L

    /** A code and when it landed. */
    data class LoginCode(val code: String, val arrivedAt: Long)

    /**
     * Records the newest one-time code.
     *
     * Written by whichever of the socket or the catch-up poll saw the message first, and the
     * newer one wins — a phone that wakes to two codes should offer the one it can still use.
     * [arrivedAt] is the *message's* timestamp rather than now, so a code fished out of a
     * catch-up poll five minutes late is already expired by [loginCode] instead of arriving
     * fresh.
     */
    fun setLoginCode(context: Context, code: String, arrivedAt: Long) {
        if (code.isBlank()) return
        val existing = prefs(context).getLong(KEY_CODE_AT, 0L)
        if (arrivedAt < existing) return
        prefs(context).edit()
            .putString(KEY_CODE, code)
            .putLong(KEY_CODE_AT, arrivedAt)
            .apply()
    }

    /**
     * The code, if there is one and it is still fresh.
     *
     * The expiry is applied here rather than by clearing the value on a timer, because nothing
     * is guaranteed to be running to do the clearing — the process dies, the phone dozes, and
     * a value that outlived its window has to answer for itself when it is next read.
     */
    fun loginCode(context: Context, now: Long = System.currentTimeMillis()): LoginCode? {
        val code = prefs(context).getString(KEY_CODE, null)?.takeIf { it.isNotBlank() } ?: return null
        val at = prefs(context).getLong(KEY_CODE_AT, 0L)
        if (at <= 0L) return null
        // Also rejects a timestamp in the future, which is what a clock correction between the
        // Mac and the phone looks like, and which would otherwise pin a code indefinitely.
        val age = now - at
        if (age < 0L || age > CODE_TTL_MS) return null
        return LoginCode(code, at)
    }

    /**
     * The pinned conversation guids, newest pin first.
     *
     * Newline-joined like [favorites] beside it, and for the same reason: a guid never contains a
     * newline, and one preference read beats a JSON parse on every list recomposition.
     */
    fun pins(context: Context): List<String> =
        prefs(context).getString(KEY_PINS, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun setPins(context: Context, value: List<String>) {
        prefs(context).edit().putString(KEY_PINS, value.joinToString("\n")).apply()
    }

    /** One speed-dial slot: the number a held key rings, and whose it is. */
    data class Speed(val number: String, val name: String)

    /**
     * The slot on [digit], or null if nothing has been put there.
     *
     * Stored as one line per slot, `digit\u0000number\u0000name`, rather than as JSON: three
     * fields and nine possible rows do not justify a parser, and the separator is a character
     * that cannot occur in any of them.
     */
    fun speedDial(context: Context, digit: Int): Speed? = speedDialAll(context)[digit]

    fun speedDialAll(context: Context): Map<Int, Speed> {
        val raw = prefs(context).getString(KEY_SPEED_DIAL, null) ?: return emptyMap()
        val out = HashMap<Int, Speed>()
        for (line in raw.split('\n')) {
            val parts = line.split('\u0000')
            if (parts.size != 3) continue
            val digit = parts[0].toIntOrNull() ?: continue
            if (parts[1].isBlank()) continue
            out[digit] = Speed(parts[1], parts[2])
        }
        return out
    }

    fun setSpeedDial(context: Context, digit: Int, number: String, name: String) {
        if (digit !in 1..9 || number.isBlank()) return
        val next = speedDialAll(context).toMutableMap()
        next[digit] = Speed(number, name)
        prefs(context).edit()
            .putString(
                KEY_SPEED_DIAL,
                next.entries.joinToString("\n") { "${it.key}\u0000${it.value.number}\u0000${it.value.name}" },
            )
            .apply()
    }

    /**
     * Forgets the slot on [digit].
     *
     * Written as the whole map rather than by removing one line, because the storage is one
     * string: there is no key to remove, only a value to rewrite without it.
     */
    fun clearSpeedDial(context: Context, digit: Int) {
        val next = speedDialAll(context).toMutableMap()
        if (next.remove(digit) == null) return
        prefs(context).edit()
            .putString(
                KEY_SPEED_DIAL,
                next.entries.joinToString("\n") { "${it.key}\u0000${it.value.number}\u0000${it.value.name}" },
            )
            .apply()
    }

    /** Sign out: wipe the stored password. */
    fun signOut(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
