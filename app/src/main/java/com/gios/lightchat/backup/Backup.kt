package com.gios.lightchat.backup

import android.content.Context
import com.gios.light.common.sync.LightSyncBackup
import com.gios.light.common.sync.LogicalStore
import com.gios.light.common.sync.SyncableStore
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * What LightChat hands to LightSync.
 *
 * Registered in the manifest and nowhere else — LightSync finds apps by provider authority
 * suffix, and the base class rejects any caller that is not LightSync by package and by signing
 * certificate. Nothing in this app calls into it.
 *
 * ### What is worth backing up, and what is not
 *
 * This app has three kinds of state on disk, and only one of them belongs in a backup.
 *
 *  - **The `chat` preferences.** Small, and mostly unrecoverable: the server URL, the pins and
 *    favourites, the speed-dial slots, the "notify unknown senders" choice, the cached contact
 *    index, and the set of conversations whose LightNotebook note has been opened. None of that
 *    exists anywhere but this phone. BlueBubbles has no concept of a pin or a favourite, so a
 *    restore without them means rebuilding the list by hand. This is [Settings], below.
 *
 *  - **`lightchat.db`.** The message and chat cache. Deliberately **not** backed up. Every row in
 *    it is a verbatim copy of something the BlueBubbles Server still holds — the store exists to
 *    make the list render offline and instantly, not to be the only copy — so restoring it would
 *    duplicate megabytes that the first sync fetches anyway, and would restore them *stale*: a
 *    thread read on the old phone would come back with a month-old tail and then be corrected.
 *    The one thing in this app that genuinely cannot be re-fetched is the per-conversation note
 *    on the contact page, and that note is not here: LightChat only deep-links to LightNotebook,
 *    which owns the text and backs it up itself. All this app holds is the flag saying a note has
 *    been opened, which is a preference and travels above.
 *
 *  - **The attachment and camera caches.** Under `cacheDir`, so they are outside what
 *    [com.gios.light.common.sync.Contents] can even name, and that is the right answer: they are
 *    the largest thing the app writes and every byte is re-fetchable from the server.
 *
 * ### The server password does not travel, on purpose
 *
 * The BlueBubbles password is stored encrypted by an AES key that lives in the AndroidKeyStore
 * (see `api.SecureStore`). That key is non-exportable and hardware-backed: it cannot leave the
 * phone, and it does not survive a factory reset. Copying the ciphertext into a backup would
 * produce something that restores cleanly and then decrypts to nothing — a backup that looks like
 * a backup and is not one. So [Settings] exports every preference *except* the password, and the
 * restored app asks for it once on the Setup screen. The server URL is right there beside it, so
 * that is one field, not two.
 *
 * Exporting the password in portable form was the alternative and was rejected: it would mean
 * writing the one secret this app holds in plaintext into an archive, to save one typed field.
 */
class Backup : LightSyncBackup() {

    override fun label() = "Chat"

    override fun stores(): List<SyncableStore> = listOf(Settings)
}

/**
 * The `chat` preferences, minus the password, as JSON.
 *
 * A [LogicalStore] rather than a `FileStore` over `shared_prefs/chat.xml`, and the reason is the
 * KeyStore blob described above: a file copy has no way to leave one key behind. Filtering here
 * is also what keeps the restore honest — the restored phone has no `bb_password` entry at all,
 * so `Store.hasPassword` is plainly false and the app opens on Setup, instead of holding a value
 * that fails to decrypt and looking like a broken login.
 *
 * The format is a flat JSON object of `key -> {"t": type, "v": value}`. Verbose for what it is,
 * but SharedPreferences is typed and `getLong` on a value written back as a String throws
 * `ClassCastException` at the read site rather than at the restore — which would be a crash on
 * the next launch, in a different file, with nothing pointing back here. JSON rather than a
 * serialised map because a future build of this app has to be able to read it, and a person
 * should be able to see what left the phone.
 */
private object Settings : LogicalStore("settings") {

    private const val PREFS = "chat"

    /**
     * Sealed by an AndroidKeyStore key, so it is not portable and is never written.
     * Spelled out rather than imported from `api.Store`, where it is private — one string
     * duplicated is cheaper than widening that object's surface for a backup.
     */
    private const val KEY_PASSWORD = "bb_password"

    override fun sizeHint(context: Context): Long =
        java.io.File(context.dataDir, "shared_prefs/$PREFS.xml").let { if (it.isFile) it.length() else 0L }

    override fun exportTo(context: Context, out: OutputStream) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = JSONObject()
        for ((key, value) in prefs.all) {
            if (key == KEY_PASSWORD) continue
            val type = when (value) {
                is Boolean -> "b"
                is Int -> "i"
                is Long -> "l"
                is Float -> "f"
                is String -> "s"
                // Nothing in Store writes a StringSet, and a null value cannot be put back
                // in a way that means anything. Skipping one key beats failing the export.
                else -> continue
            }
            root.put(key, JSONObject().put("t", type).put("v", value))
        }
        out.writer(Charsets.UTF_8).use { it.write(root.toString()) }
    }

    override fun restoreFrom(context: Context, input: InputStream) {
        val text = input.reader(Charsets.UTF_8).use { it.readText() }
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        // Not `clear()`: the password this phone was just given by hand is in the same file,
        // and wiping it to write a backup that deliberately does not contain one would undo
        // a setup the user may have already completed.
        for (key in root.keys()) {
            if (key == KEY_PASSWORD) continue
            val entry = root.optJSONObject(key) ?: continue
            when (entry.optString("t")) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "s" -> editor.putString(key, entry.optString("v"))
            }
        }
        // commit(), not apply(): LightSyncBackup ends the process after a restore, and an
        // apply() still in flight when it does is a restore that silently did nothing.
        editor.commit()
    }
}
