package com.gios.lightchat.db

import android.util.Log
import com.gios.lightchat.ChatMessage
import com.gios.lightchat.Conversation
import com.gios.lightchat.advancedBy
import com.gios.lightchat.api.BlueBubblesApi
import org.json.JSONObject

/**
 * Keeping [MessageStore] level with the Mac, fetching as little as possible to do it.
 *
 * The shape of the thing: **one date is the whole protocol.** [MessageStore.syncedAt] is
 * the newest message the phone has ever been told about, and every sync is
 * `message/query?after=<that>`. On a phone that was closed for ten minutes that is a
 * request returning nothing; on one closed for a week it is a handful of pages. Neither
 * is the thousand-message sweep of the entire account that used to run on every single
 * launch, and the list is on screen before either has finished, because the list comes off
 * disk.
 *
 * What this deliberately does *not* do is fetch thread history. A conversation's messages
 * arrive when the conversation is opened, and older ones when they are scrolled to. A
 * chat you have not looked at since 2023 costs one row in `chats` and nothing else.
 */
class Sync(private val api: BlueBubblesApi, private val store: MessageStore) {

    /**
     * Brings the conversation list up to date.
     *
     * @return the conversations as they now stand, or null if nothing was reachable.
     */
    fun refreshList(): List<Conversation>? {
        val seeded = store.syncedAt() > 0L
        return if (seeded) delta() else seed()
    }

    /**
     * First run: learn what the conversations *are*, and nothing more.
     *
     * The existing sweep is reused as-is — one query, the newest [SEED_MESSAGES] messages
     * across the account, collapsed into a conversation per chat (including the
     * forked-group merge, which is why this can't just be a `chat/query`). Their *contents*
     * are thrown away rather than stored: they are the newest few messages of whichever
     * chats happened to be busy, so keeping them would leave most threads holding a
     * scattered handful with holes under them, which is worse than holding none. Threads
     * fill in properly, in order, when opened.
     */
    private fun seed(): List<Conversation>? {
        // Deliberately not caught: a 401 here is a rotated password and has to reach
        // handleError so the app signs out, and a first run that fails with nothing cached
        // must show an error rather than an empty list that looks like an empty account.
        val convos = api.conversations(limit = SEED_MESSAGES)
        store.putChats(convos)
        // The watermark starts at the newest thing the seed saw, so the first delta asks
        // for everything since — not for the whole account again.
        convos.maxOfOrNull { it.lastDate }?.let(store::setSyncedAt)
        Log.d(TAG, "seeded ${convos.size} conversations")
        return store.chats()
    }

    /**
     * Everything created since the last sync, and nothing else.
     *
     * Pages ASC from the watermark so a long absence is walked oldest-first and the
     * watermark can be advanced after each page — interrupted halfway, the next run
     * resumes rather than restarting. Capped at [MAX_DELTA_PAGES]: past that the phone has
     * been away long enough that the remaining backlog is history, and it will be fetched
     * per-thread on demand like any other old message.
     */
    private fun delta(): List<Conversation>? {
        val since = store.syncedAt()
        var offset = 0
        var pages = 0
        var newest = since
        val touched = HashSet<String>()
        var reached = false

        while (pages < MAX_DELTA_PAGES) {
            // The first page propagates — that is the request whose failure means the server
            // is unreachable or the password is wrong, and the caller has to hear about it.
            // Later pages are best-effort: the watermark has already advanced past what did
            // arrive, so the next sync resumes from there rather than starting over.
            val page = if (pages == 0) {
                api.messagePageJson(after = since, limit = DELTA_PAGE, offset = offset, sort = "ASC")
            } else {
                runCatching {
                    api.messagePageJson(after = since, limit = DELTA_PAGE, offset = offset, sort = "ASC")
                }.onFailure { Log.d(TAG, "delta page $pages failed: $it") }.getOrNull() ?: break
            }

            pages++
            val rows = ArrayList<MessageStore.Row>(page.length())
            // Chat rows are accumulated and written once per page. Reading and writing a
            // conversation per message meant a query per row, and a busy group's page is a
            // couple of hundred messages all belonging to the same one.
            val byGuid = HashMap<String, Conversation>()
            val parsed = HashMap<String, ChatMessage>(page.length())
            for (i in 0 until page.length()) {
                val raw = page.optJSONObject(i) ?: continue
                val message = BlueBubblesApi.parseMessage(raw)
                parsed[message.guid] = message
                newest = maxOf(newest, message.date)
                // The chats array is stripped before storing: it is the same chat object
                // repeated on every message of that chat, and `chats` already holds one
                // copy. On a two-hundred-message page that is most of the bytes.
                val chats = raw.optJSONArray("chats")
                val slim = JSONObject(raw.toString()).apply { remove("chats") }.toString()
                for (j in 0 until (chats?.length() ?: 0)) {
                    val chat = chats!!.getJSONObject(j)
                    val guid = chat.optString("guid").takeIf { it.isNotBlank() } ?: continue
                    rows.add(MessageStore.Row(guid, message, slim))
                    touched.add(guid)
                    // Through the room mapping, not by room guid: a forked group's sibling
                    // room resolves to the one collapsed conversation instead of being
                    // inserted as a duplicate list row that never merges back.
                    val current = byGuid[guid] ?: store.conversationForRoom(guid)
                    val key = current?.guid ?: guid
                    byGuid[key] = current?.advancedBy(message) { parsed[it] }
                        ?: BlueBubblesApi.conversationFrom(chat, guid, message)
                }
            }
            store.putChats(byGuid.values.toList())
            // Only rooms whose thread has been opened are worth storing messages for. For
            // the rest the list row is the whole of what the app shows, and keeping their
            // messages would be storing history nobody asked for — the thing this change
            // exists to stop.
            store.putMessages(rows.filter { store.isThreadLoaded(it.chatGuid) })
            store.setSyncedAt(newest)

            if (page.length() < DELTA_PAGE) { reached = true; break }
            offset += DELTA_PAGE
        }

        if (!reached && pages >= MAX_DELTA_PAGES) {
            // **The cap skips the newest, not the oldest.** Paging ASC from the watermark
            // means the pages walked are the *start* of the backlog, so stopping leaves
            // everything after it unseen — including today. A list frozen two thousand
            // messages in the past would then only creep forward one cap per refresh. So
            // when the backlog is deeper than we are willing to walk, jump: take the newest
            // page instead and move the watermark to the end of it. What is skipped is then
            // the middle, which is history, and history is fetched per thread on demand.
            Log.w(TAG, "backlog deeper than ${MAX_DELTA_PAGES * DELTA_PAGE}; jumping to the newest")
            val head = runCatching {
                api.messagePageJson(limit = DELTA_PAGE, sort = "DESC")
            }.getOrNull()
            var headNewest = newest
            for (i in 0 until (head?.length() ?: 0)) {
                val raw = head!!.optJSONObject(i) ?: continue
                headNewest = maxOf(headNewest, BlueBubblesApi.messageDate(raw))
            }
            if (headNewest > newest) {
                store.setSyncedAt(headNewest)
                // The list is rebuilt from the sweep rather than from the pages we skipped,
                // which is the only way for it to be right about chats inside the gap.
                runCatching { api.conversations(limit = SEED_MESSAGES) }
                    .getOrNull()?.let(store::putChats)
            }
        }
        touched.forEach { store.trim(it) }
        if (pages == 0) return null
        return store.chats()
    }

    /* ---------------- threads ---------------- */

    /**
     * A thread's messages, cheapest source first.
     *
     * Never fetched before it is opened, and once opened only the part that changed is
     * fetched again. The first open of a chat is the one request this makes that looks like
     * the old behaviour — after that, reopening a conversation you were just in asks the
     * server for messages after the newest one held, which on a quiet thread is an empty
     * answer.
     */
    fun thread(conversation: Conversation): List<ChatMessage> {
        val guids = conversation.guids
        if (!guids.any { store.isThreadLoaded(it) }) return firstLoad(guids)
        for (guid in guids) {
            val newest = store.newestDate(guid)
            if (newest <= 0L) { firstLoad(listOf(guid)); continue }
            // Paged, because "what arrived since you last looked" is not bounded by one
            // page. A single 50-message fetch of the newest would leave a hole between them
            // and the rows already held, and the backfill anchors below the hole, so it
            // would never be filled.
            var offset = 0
            var pages = 0
            while (pages < MAX_THREAD_CATCHUP_PAGES) {
                val got = fetchInto(
                    guid,
                    after = newest,
                    limit = MessageStore.PAGE,
                    offset = offset,
                    sort = "ASC",
                )
                pages++
                if (got.returned < MessageStore.PAGE) break
                offset += MessageStore.PAGE
            }
        }
        guids.forEach { store.trim(it) }
        return store.messages(guids, limit = MessageStore.PAGE)
    }

    private fun firstLoad(guids: List<String>): List<ChatMessage> {
        guids.forEach { fetchInto(it, limit = MessageStore.PAGE) }
        return store.messages(guids, limit = MessageStore.PAGE)
    }

    /**
     * One page further back, on request — scrolling to the top of what is held.
     *
     * @return the messages now held for the thread, or null when the server had nothing
     *   older, which is recorded so the same dead page is not asked for twice.
     */
    fun olderThan(conversation: Conversation, window: Int): List<ChatMessage>? {
        var returned = 0
        for (guid in conversation.guids) {
            // Anchored on what this *room* holds, not on the merged display window. The
            // window is the newest N across all of a forked group's rooms, so using its
            // oldest element would ask the live room for a page it already has and a
            // dormant sibling for one from years before the gap.
            val oldest = store.oldestDate(guid)
            if (oldest <= 0L) continue
            returned += fetchInto(guid, before = oldest, limit = MessageStore.PAGE).returned
        }
        // **Emptiness is decided by what the server returned, not by what was new to us.**
        // Judging it by novelty was wrong in a way that stranded conversations for good: the
        // display window is smaller than what the store holds, so the first scroll-up in any
        // well-stocked chat re-fetched rows already held, counted zero new ones, and latched
        // "no more history" permanently. `before` is inclusive, so the boundary message
        // comes back every time and a page of one is still an empty answer.
        if (returned <= conversation.guids.size) return null
        conversation.guids.forEach { store.trim(it) }
        return store.messages(conversation.guids, limit = window)
    }

    /**
     * Fetches one page for one room and stores it.
     *
     * @return how many of the returned messages were not already held, which is what tells
     *   a backfill whether it made progress.
     */
    private fun fetchInto(
        guid: String,
        after: Long? = null,
        before: Long? = null,
        limit: Int,
        offset: Int = 0,
        sort: String = "DESC",
    ): Fetched {
        val page = runCatching {
            api.messagePageJson(
                after = after,
                before = before,
                chatGuid = guid,
                limit = limit,
                offset = offset,
                sort = sort,
                // The room is already known — asking for its chat object on every message
                // is the bulk of the response for no gain.
                withChats = false,
            )
        }.onFailure { Log.d(TAG, "thread fetch for $guid failed: $it") }.getOrNull()
            ?: return Fetched(0, 0)

        val rows = ArrayList<MessageStore.Row>(page.length())
        for (i in 0 until page.length()) {
            val raw = page.optJSONObject(i) ?: continue
            rows.add(MessageStore.Row(guid, BlueBubblesApi.parseMessage(raw), raw.toString()))
        }
        store.putMessages(rows)
        // Here rather than only in firstLoad: a room added to a forked group later is one
        // the thread has now fetched, and without the flag the delta and the socket both
        // drop its messages on the floor — and it is typically the *live* room.
        store.setThreadLoaded(guid)
        return Fetched(returned = page.length(), stored = rows.size)
    }

    /** What one page request produced. [returned] is the server's answer size, which is
     *  what says whether there is more; [stored] is how many parsed. */
    private data class Fetched(val returned: Int, val stored: Int)

    companion object {
        private const val TAG = "Sync"

        /** The seed sweep. Same size the list load has always used. */
        private const val SEED_MESSAGES = 1000

        /** Messages per delta page. */
        private const val DELTA_PAGE = 200

        /** How far a reopen will chase a gap before leaving the rest to the backfill. */
        private const val MAX_THREAD_CATCHUP_PAGES = 6

        /** Roughly a very busy fortnight. Past it, the rest is history and is fetched per
         *  thread on demand rather than all at once on a launch. */
        private const val MAX_DELTA_PAGES = 10
    }
}
