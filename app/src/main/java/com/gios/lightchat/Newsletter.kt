package com.gios.lightchat

import org.json.JSONArray
import org.json.JSONObject

/**
 * One recipient inside a newsletter batch.
 *
 * Two shapes, and the distinction is load-bearing rather than cosmetic. A **chat** is a room
 * that already exists on the server and is addressed by its guid — the only way to name a
 * *group*, since a group has no handle. A **contact** is a person, addressed by handle, whose
 * 1:1 guid can simply be constructed (`iMessage;-;<handle>`) and therefore works for a thread
 * that does not exist on this phone yet. Constructing a guid for a group is exactly the bug
 * this split avoids: `iMessage;-;<anything>` is by definition a two-person chat, so a group
 * stored as a handle would quietly deliver to one member instead of the room.
 *
 * [label] is captured when the target is added rather than resolved at send time. The address
 * book and the conversation list are both refreshed from the server, and a batch the user named
 * six weeks ago should still read as the people they picked even if a chat has since fallen out
 * of the sweep window or been renamed.
 */
data class NewsletterTarget(
    /** Chat guid when [isChat]; the raw handle (phone / email) otherwise. */
    val id: String,
    val label: String,
    val isChat: Boolean,
) {
    /** Stable identity for de-duplication inside a batch — a chat and a contact could in
     *  principle carry the same string, and they are not the same recipient. */
    val key: String get() = (if (isChat) "c:" else "h:") + id.lowercase()
}

/**
 * A named set of recipients one message goes out to.
 *
 * Deliberately **not** a group chat: sending walks the targets and posts the same message to
 * each separately, so nobody sees who else received it and nobody can reply to the room. That
 * is the whole point — it is a mailing list, not a room. See `ChatViewModel.sendNewsletter`.
 */
data class NewsletterBatch(
    val id: String,
    val name: String,
    val targets: List<NewsletterTarget> = emptyList(),
) {
    /** The batch-list subtitle: how many go out, and the first few of them. */
    fun summary(): String {
        if (targets.isEmpty()) return "No recipients"
        val head = targets.take(3).joinToString(", ") { it.label }
        val rest = targets.size - 3
        return if (rest > 0) "$head +$rest more" else head
    }

    companion object {
        /** The display name for a batch the user never named. Kept here so the list, the
         *  editor and the compose header cannot disagree about what an unnamed batch is
         *  called. */
        const val UNTITLED = "Untitled batch"
    }
}

/**
 * How far a newsletter send has got.
 *
 * Held in `UiState` and rendered by the compose screen, because a send to forty recipients is
 * the one thing in this app that takes long enough for "Sending…" to be an unhelpful answer.
 *
 * **[batchId] is what stops this being reported about the wrong batch.** There is one of these
 * for the whole app, so a broadcast the user backed out of is still running while they open
 * another batch — and without an id on it, that second batch's screen would show the first
 * one's outcome as its own.
 *
 * [failed] and [partial] carry labels rather than ids: their only reader is a line of text
 * naming who missed what, and by then the target that produced them is gone. The two are
 * separate because "got nothing" and "got the words but not the photos" call for different
 * things from the user, and merging them would make re-running the batch — which double-sends
 * to everyone who succeeded — look like the fix for both.
 */
data class NewsletterProgress(
    val batchId: String,
    val batchName: String,
    val sent: Int,
    val total: Int,
    val failed: List<String> = emptyList(),
    val partial: List<String> = emptyList(),
    val done: Boolean = false,
    /** Set when the broadcast never started — nothing was sent and [sent]/[failed] say
     *  nothing useful, so this replaces the whole line rather than decorating it. */
    val error: String? = null,
) {
    /** The status line: in-flight progress, or the outcome once [done]. */
    fun line(): String {
        error?.let { return it }
        if (!done) return "Sending $sent/$total…"
        val notes = buildList {
            if (failed.isNotEmpty()) add("failed: " + failed.joinToString(", "))
            if (partial.isNotEmpty()) add("photos missing: " + partial.joinToString(", "))
        }
        val head = if (sent == 0) "Couldn’t send to anyone" else "Sent to $sent"
        return if (notes.isEmpty()) head else head + " — " + notes.joinToString(" — ")
    }
}

/**
 * JSON for the batches, for [com.gios.lightchat.api.Store].
 *
 * JSON rather than the newline-joined form used by favorites and pins next door: a batch is a
 * name plus a list of two-field records, and the delimiter tricks that work for a list of guids
 * (which cannot contain a newline) stop being safe once a user-typed *name* is in the payload.
 */
object NewsletterJson {

    fun encode(batches: List<NewsletterBatch>): String {
        val array = JSONArray()
        for (batch in batches) {
            val targets = JSONArray()
            for (t in batch.targets) {
                targets.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("label", t.label)
                        .put("chat", t.isChat),
                )
            }
            array.put(
                JSONObject()
                    .put("id", batch.id)
                    .put("name", batch.name)
                    .put("targets", targets),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<NewsletterBatch> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val targetsJson = obj.optJSONArray("targets") ?: JSONArray()
                val targets = (0 until targetsJson.length()).mapNotNull { j ->
                    val t = targetsJson.optJSONObject(j) ?: return@mapNotNull null
                    val tid = t.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    NewsletterTarget(
                        id = tid,
                        label = t.optString("label").takeIf { it.isNotBlank() && it != "null" } ?: tid,
                        isChat = t.optBoolean("chat", false),
                    )
                }
                NewsletterBatch(id = id, name = obj.optString("name"), targets = targets)
            }
        }.getOrDefault(emptyList())
    }
}
