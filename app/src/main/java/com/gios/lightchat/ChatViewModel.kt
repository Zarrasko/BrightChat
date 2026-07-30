package com.gios.lightchat

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.lightchat.api.ApiException
import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.api.Store
import com.gios.lightchat.db.MessageStore
import com.gios.lightchat.db.Sync
import com.gios.lightchat.socket.AppForeground
import com.gios.lightchat.socket.SocketBus
import com.gios.lightchat.socket.SocketService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Status { Idle, Loading, Ready, Error }

// Send a typing "stop" this long after the last keystroke; expire a received
// "typing" after this long without a refresh (the server re-emits ~every 5s).
private const val TYPING_PAUSE_MS = 4_000L
private const val TYPING_EXPIRY_MS = 12_000L

/** Coming back to the app inside this window doesn't re-pull the list — it can't
 *  have gone stale, and a cold start's init refresh would otherwise be cancelled by
 *  the onStart one landing a few hundred milliseconds later. */
private const val RESUME_REFRESH_MIN_GAP_MS = 3_000L

data class UiState(
    val isConfigured: Boolean,                 // a server URL and password are stored
    val status: Status = Status.Idle,
    val conversations: List<Conversation> = emptyList(),
    val open: Conversation? = null,            // the currently-open thread, if any
    val messages: List<ChatMessage> = emptyList(),
    val threadLoading: Boolean = false,
    val loadingOlder: Boolean = false,         // a history page is in flight (see loadOlder)
    val historyExhausted: Boolean = false,     // the server has no messages older than these
    val threadWindow: Int = 0,                 // how many of the open thread's messages are shown
    val contacts: Contacts = Contacts(),       // address → name, from the server's address book
    val contactList: List<Contact> = emptyList(), // searchable recipients for a new message
    val composingNew: Boolean = false,         // the "New message" compose screen is open
    val privateApi: Boolean = false,           // server's Private API live → tapbacks available
    val typingChatGuid: String? = null,        // chat whose other party is currently typing
    val favorites: Set<String> = emptySet(),   // starred chat guids (local, see Store.favorites)
    val message: String? = null,               // transient status / error line
)

/**
 * Single source of truth for the BlueBubbles client. Owns password setup, the
 * conversation list, the open thread, sending, and the live feed.
 *
 * Reads (REST, [BlueBubblesApi]): [refresh] re-pulls the list, [open] pulls a
 * thread. Writes: [sendMessage] posts optimistically then reconciles with the
 * server's echo. Live: it collects [SocketBus] (fed by the foreground
 * [SocketService]) and folds new/updated messages into the list and open thread.
 * Ordering is enforced here — conversations by last activity, messages by date.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application

    // A client only exists once setup has stored both a server URL and a password.
    private var api: BlueBubblesApi? = run {
        val url = Store.baseUrl(application)
        val pw = Store.password(application)?.takeIf { it.isNotBlank() }
        if (url != null && pw != null) BlueBubblesApi(url, pw) else null
    }

    /**
     * The phone's own copy of the list and of whatever threads have been opened. The app
     * used to re-download the newest thousand messages on the account on every launch just
     * to know what the list said; now it reads that off disk and fetches only the delta.
     */
    private val store = MessageStore.get(application)
    private var sync: Sync? = api?.let { Sync(it, store) }

    private val _state = MutableStateFlow(
        UiState(
            isConfigured = api != null,
            privateApi = Store.privateApi(application),
            favorites = Store.favorites(application),
            // Straight off disk, synchronously, before anything is on screen: the list is
            // the first thing drawn and there is no reason for it to be empty while a
            // network round trip decides what it should have said. Also what makes the app
            // usable with the tunnel down.
            conversations = if (api != null) store.chats() else emptyList(),
            contacts = Store.contacts(application),
        ),
    )
    val state: StateFlow<UiState> = _state

    private var loadJob: Job? = null

    /** When [refresh] last started, for [refreshOnResume]'s guard. */
    private var lastRefreshAt = 0L
    private var threadJob: Job? = null

    // The address book is small (hundreds of contacts) and changes rarely, so we
    // fetch it once per session alongside the first conversation load and cache it.
    private var contacts = Store.contacts(application)
    private var contactList = emptyList<Contact>()
    private var contactsLoaded = false

    // Per-conversation message cache (session-lived). Reopening a thread shows the
    // cached messages instantly while a fresh fetch refreshes in the background —
    // no "Loading…" flash. Snapshotted on close so live updates persist. Holds the
    // *raw* list (reaction messages included) so reopening re-folds correctly.
    private val messageCache = HashMap<String, List<ChatMessage>>()

    // Per-conversation (keyed by primary guid) the forked-group room guid that last
    // delivered, so AppleScript sends retry it first instead of re-probing a dead room
    // every time (see sendTargets). Session-lived; the Private API path ignores it.
    private val lastGoodRoom = HashMap<String, String>()

    // The open thread's raw messages — the single source for what's shown. State's
    // `messages` is always foldReactions(openRaw); every open-thread mutation goes
    // through updateOpenThread so tapbacks stay folded onto their targets.
    private var openRaw: List<ChatMessage> = emptyList()

    // Per-conversation (primary guid → lastDate at the time) unread markers cleared
    // on this device. Papers over the window between our markRead and the server's
    // chat.db reflecting it — without this a refresh would re-derive unread from a
    // not-yet-stamped dateRead and resurrect the dot on a thread just read here. A
    // newer message (lastDate past the recorded one) shows unread again. Session-
    // lived: across launches the server's own read state is correct.
    private val clearedUnread = HashMap<String, Long>()

    // A chat to open as soon as the conversation list has loaded — set when a
    // notification tap arrives before the list exists (cold start).
    private var pendingOpenGuid: String? = null

    init {
        observeSocket()
        if (api != null) {
            refresh()
            startSocket()
        }
    }

    // ---- Setup ------------------------------------------------------------

    /** First-launch setup: store the server URL + password and validate them
     *  against the server before committing. Both are required. */
    fun saveSetup(url: String, password: String) {
        val pw = password.trim()
        if (url.isBlank() || pw.isEmpty()) return
        _state.update { it.copy(status = Status.Loading, message = "Connecting…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = connectClient(url, pw, "Couldn’t reach the server — check the URL and password")
                ?: return@launch
            Store.setPassword(app, pw)
            _state.update { it.copy(isConfigured = true) }
            loadConversations()
            startSocket()
        }
    }

    /** Changes the server URL from Settings: re-validate against the existing
     *  password, then recreate the client and reconnect the socket to the new host. */
    fun updateServerUrl(url: String) {
        if (url.isBlank()) return
        val pw = Store.password(app)?.takeIf { it.isNotBlank() } ?: return
        _state.update { it.copy(status = Status.Loading, message = "Connecting…") }
        viewModelScope.launch(Dispatchers.IO) {
            connectClient(url, pw, "Couldn’t reach that server — check the URL") ?: return@launch
            // Bounce the socket so it reconnects to the new host.
            stopSocket()
            startSocket()
            loadConversations()
        }
    }

    /**
     * Shared tail of [saveSetup]/[updateServerUrl]: normalizes + stores [url] (the
     * same way it's read back), validates it against [pw], and installs the client
     * as [api], caching the Private API flag. Returns null — with [failureMessage]
     * surfaced as an error — when the server can't be reached.
     */
    private fun connectClient(url: String, pw: String, failureMessage: String): BlueBubblesApi? {
        Store.setBaseUrl(app, url)
        val base = Store.baseUrl(app) ?: return null
        val client = BlueBubblesApi(base, pw)
        val info = runCatching { client.serverInfo() }.getOrNull()
        if (info?.reachable != true) {
            _state.update { it.copy(status = Status.Error, message = failureMessage) }
            return null
        }
        api = client
        sync = Sync(client, store)
        Store.setPrivateApi(app, info.privateApiReady)
        _state.update { it.copy(privateApi = info.privateApiReady, message = null) }
        return client
    }

    // ---- Conversation list ------------------------------------------------

    fun refresh() {
        if (api == null) return
        lastRefreshAt = SystemClock.elapsedRealtime()
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) { loadConversations() }
    }

    /**
     * Re-pull the list when the app comes back to the foreground
     * (`MainActivity.onStart`). The live socket keeps the list current while the app
     * is up, but it only runs as a foreground service — anything that happened while
     * the process was dead, or while the tunnel was down, is missed, and the list
     * would otherwise show whatever it showed when you left.
     *
     * Rate-guarded because `init` already refreshes on a cold start, and onStart
     * fires immediately after it; without the guard every launch would fire two
     * identical requests and the second would cancel the first.
     */
    fun refreshOnResume() {
        if (api == null) return
        // The open thread first, and unconditionally. Leaving the app from inside a chat
        // leaves `open` set, so coming back re-shows that thread from the cache — if the
        // socket missed anything (process killed, tunnel down, Doze), the reply simply
        // wasn't there and nothing was going to fetch it. This is the "it didn't check
        // whether they texted back" case, and it isn't covered by refreshing the list.
        _state.value.open?.let { reopenThread(it) }
        if (SystemClock.elapsedRealtime() - lastRefreshAt < RESUME_REFRESH_MIN_GAP_MS) return
        refresh()
    }

    /** Re-fetches [conversation]'s messages without the open-thread reset [open] does —
     *  no "Loading…", no scroll change, the cached list stays on screen until the fresh
     *  one lands. */
    private fun reopenThread(conversation: Conversation) {
        val syncer = sync ?: return
        threadJob?.cancel()
        threadJob = viewModelScope.launch(Dispatchers.IO) {
            val msgs = runCatching { syncer.thread(conversation) }.getOrNull().orEmpty()
            if (msgs.isEmpty()) return@launch
            messageCache[conversation.guid] = msgs
            if (_state.value.open?.guid == conversation.guid) {
                openRaw = msgs
                _state.update { it.copy(messages = foldReactions(msgs), threadLoading = false) }
            }
        }
    }

    private suspend fun loadConversations() {
        val client = api ?: return
        val syncer = sync ?: return
        // Loading only when there is nothing to show. With rows on disk the list is already
        // on screen and correct as of the last sync; putting a spinner over it to fetch a
        // delta that is usually empty would be a worse app than the one that had no cache.
        val hadRows = _state.value.conversations.isNotEmpty()
        if (!hadRows) _state.update { it.copy(status = Status.Loading, message = null) }
        try {
            val synced = syncer.refreshList()
            if (synced == null && hadRows) {
                // Unreachable, but the phone still knows what it knew. Stay on the cached
                // list rather than throwing it away or showing an error over it.
                _state.update { it.copy(status = Status.Ready) }
                return
            }
            val convos = (synced ?: store.chats()).sortedByDescending { it.lastDate }
                // Honor unreads already cleared on this device (see clearedUnread).
                .map { c ->
                    if (c.unread && (clearedUnread[c.guid] ?: 0L) >= c.lastDate) c.copy(unread = false) else c
                }
            // Re-check Private API liveness so enabling/disabling it on the server
            // (or the helper dropping) reflects without re-running setup.
            val privateApi = runCatching { client.serverInfo() }.getOrNull()
                ?.also { Store.setPrivateApi(app, it.privateApiReady) }
                ?.privateApiReady ?: _state.value.privateApi
            if (!contactsLoaded) {
                runCatching { client.contacts() }.onSuccess { raw ->
                    contacts = Contacts.from(raw)
                    // Persist so SocketService can name notification senders even
                    // when the app (and this ViewModel) isn't running.
                    Store.setContacts(app, contacts.asMap())
                    // One pickable row per address (a person may have several),
                    // newest search needs name→address, sorted for the picker.
                    contactList = raw.map { Contact(name = it.second, address = it.first) }
                        .distinctBy { it.address }
                        .sortedBy { it.name.lowercase() }
                    contactsLoaded = true
                }
            }
            _state.update {
                it.copy(
                    status = Status.Ready,
                    conversations = convos,
                    contacts = contacts,
                    contactList = contactList,
                    privateApi = privateApi,
                    message = null,
                )
            }
            // A notification tap that landed before the list existed (cold start) —
            // open its thread now, unless the user has already navigated somewhere.
            pendingOpenGuid?.let { guid ->
                pendingOpenGuid = null
                if (_state.value.open == null && !_state.value.composingNew) {
                    convos.firstOrNull { guid in it.guids }?.let(::open)
                }
            }
        } catch (t: Throwable) {
            handleError(t)
        }
    }

    /**
     * Opens the conversation containing [chatGuid] — the notification deep link.
     * If the list isn't loaded yet (app launched from the notification), the open
     * is queued and fires when [loadConversations] lands.
     */
    fun openByGuid(chatGuid: String) {
        val convo = _state.value.conversations.firstOrNull { chatGuid in it.guids }
        if (convo != null) {
            open(convo)
        } else {
            pendingOpenGuid = chatGuid
            refresh()
        }
    }

    // ---- One thread -------------------------------------------------------

    fun open(conversation: Conversation) {
        // The in-memory cache is free and renders this frame. The on-disk one is read a
        // moment later, in the thread job — parsing fifty messages and their attachment
        // metadata is not something to do on the frame that handles the tap.
        val cached = messageCache[conversation.guid]
        openRaw = cached ?: emptyList()
        _state.update {
            it.copy(
                open = conversation,
                messages = cached?.let(::foldReactions) ?: emptyList(),
                threadLoading = cached == null,
                loadingOlder = false,
                historyExhausted = false,
                // Every thread starts showing its newest page and grows as it is scrolled.
                threadWindow = MessageStore.PAGE,
            )
        }
        conversation.guids.forEach { markReadIfPrivate(it) }
        clearUnread(conversation.guid)
        Notifications.clearChat(app, conversation.guids)
        // Opening the thread is what "you've seen it" means, so drop any alert being held
        // for it — otherwise leaving the app would post a notification for the message
        // just read. See PendingAlerts.
        PendingAlerts.clear(conversation.guids)
        // A share that arrived without a recipient was waiting for exactly this.
        flushPendingShared()
        threadJob?.cancel()
        threadJob = viewModelScope.launch(Dispatchers.IO) {
            val syncer = sync ?: return@launch
            try {
                // Disk first: reopening a thread after the process was killed used to show
                // "Loading…" and refetch a hundred messages. Now the stored copy is on
                // screen before the network is touched.
                if (cached == null) {
                    val onDisk = store.messages(conversation.guids, limit = MessageStore.PAGE)
                    if (onDisk.isNotEmpty() && _state.value.open?.guid == conversation.guid) {
                        openRaw = onDisk
                        _state.update { it.copy(messages = foldReactions(onDisk), threadLoading = false) }
                    }
                }
                // Raw — reactions included; merged across a forked group's sibling rooms
                // (usually just one guid) and re-sorted by date in foldReactions. Sync
                // decides what to ask for: everything on the first open of a chat, and
                // only what is newer than the newest message held on every open after
                // that. Each room is fetched independently so one stale or dead room of a
                // forked group can't sink the whole thread.
                val msgs = syncer.thread(conversation)
                messageCache[conversation.guid] = msgs
                if (_state.value.open?.guid == conversation.guid) {
                    openRaw = msgs
                    _state.update { it.copy(messages = foldReactions(msgs), threadLoading = false) }
                }
            } catch (t: Throwable) {
                _state.update { if (it.open?.guid == conversation.guid) it.copy(threadLoading = false) else it }
                handleError(t)
            }
        }
    }

    /**
     * Fetches the page of history under what is held, when the thread is scrolled to the top
     * of it.
     *
     * **This is the only thing in the app that asks for old messages**, and it only runs
     * because somebody scrolled to the end of a conversation. Everything else — the list,
     * the delta sync, opening a thread — deals exclusively in what is new. A chat nobody
     * scrolls back through never costs more than its most recent page.
     *
     * Guarded three ways: not while one is already in flight, not once the server has said
     * there is nothing older ([UiState.historyExhausted]), and not before the first page has
     * landed.
     */
    fun loadOlder() {
        val conversation = _state.value.open ?: return
        val syncer = sync ?: return
        if (_state.value.loadingOlder || _state.value.historyExhausted) return
        // Not while the open fetch is still running: both assign openRaw, and whichever
        // landed second won — which on a short thread meant a page of history being
        // clobbered straight back to the newest fifty.
        if (_state.value.threadLoading || threadJob?.isActive == true) return
        if (openRaw.isEmpty()) return
        // The window grows by a page each time. Returning a fixed size meant that once the
        // thread reached it, every further fetch wrote rows nobody could see, the message
        // count never changed, and the trigger never re-armed — a silent dead end a couple
        // of hundred messages back.
        val window = _state.value.threadWindow + MessageStore.PAGE
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val older = runCatching { syncer.olderThan(conversation, window) }.getOrNull()
            if (_state.value.open?.guid != conversation.guid) {
                _state.update { it.copy(loadingOlder = false) }
                return@launch
            }
            if (older == null) {
                // Nothing came back, so this is the start of the conversation. Recorded in
                // the store as well as in state, so reopening the thread tomorrow doesn't
                // ask for the same empty page again.
                _state.update { it.copy(loadingOlder = false, historyExhausted = true) }
                return@launch
            }
            messageCache[conversation.guid] = older
            openRaw = older
            _state.update {
                it.copy(messages = foldReactions(older), loadingOlder = false, threadWindow = window)
            }
        }
    }

    /**
     * Stars / unstars a conversation (long-press in the list), moving it between the
     * Favorites and Known tabs. Local only — BlueBubbles has no favorites concept —
     * so it's written straight through to [Store] and survives a reinstall of the
     * server but not of the app. Keyed on the primary guid, like [messageCache]: a
     * forked group's other rooms all resolve to the same conversation.
     */
    fun toggleFavorite(conversation: Conversation) {
        // The write is deliberately *after* the update, not inside it: update's lambda
        // re-runs on CAS contention (a socket event landing at the same moment), which
        // would mean a duplicate SharedPreferences write and, worse, persisting a value
        // that then lost the race.
        var next = _state.value.favorites
        _state.update { s ->
            next = if (conversation.guid in s.favorites) {
                s.favorites - conversation.guid
            } else {
                s.favorites + conversation.guid
            }
            s.copy(favorites = next)
        }
        Store.setFavorites(app, next)
    }

    fun closeThread() {
        // Snapshot the raw list (incl. live updates) so reopening is instant.
        _state.value.open?.let {
            messageCache[it.guid] = openRaw
            finishTyping(it.guid) // don't leave a typing bubble up after leaving
        }
        openRaw = emptyList()
        threadJob?.cancel()
        _state.update { it.copy(open = null, messages = emptyList(), threadLoading = false) }
    }

    /**
     * Permanently deletes a conversation from Messages on the Mac (swipe-to-delete in
     * the list). Private-API only — the server gates `DELETE /chat/:guid` on it. A
     * forked group spans several rooms, so every guid is deleted. Optimistic: the row
     * disappears immediately; if any room fails we re-pull the list so it reappears.
     */
    fun deleteConversation(conversation: Conversation) {
        if (!_state.value.privateApi) {
            _state.update { it.copy(message = "Deleting needs the Private API") }
            return
        }
        val client = api ?: return
        // Drop the row now (and close it if it's the open thread); drop its cache too.
        _state.update { s ->
            s.copy(
                conversations = s.conversations.filterNot { it.guid == conversation.guid },
                open = s.open?.takeUnless { it.guid == conversation.guid },
            )
        }
        if (_state.value.open == null) { openRaw = emptyList(); threadJob?.cancel() }
        messageCache.remove(conversation.guid)
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.deleteChat(conversation.guids) } }
        messageCache.remove(conversation.guid)
        viewModelScope.launch(Dispatchers.IO) {
            val failed = conversation.guids.any { runCatching { client.deleteChat(it) }.isFailure }
            // The server can take ~30s per room (it waits for the local DB), so by now
            // the list may have moved on — a refresh reconciles either way: it restores
            // a row that failed to delete, and confirms the ones that succeeded are gone.
            loadConversations()
            // After the reload (which clears `message`), surface any failure.
            if (failed) _state.update { it.copy(message = "Couldn’t delete the conversation") }
        }
    }

    /**
     * Applies [transform] to the open thread's raw messages and republishes the
     * folded view — but only if [convoGuid] is still the open thread, so a late
     * send/echo can't clobber a thread the user has since navigated away from.
     */
    private fun updateOpenThread(convoGuid: String, transform: (List<ChatMessage>) -> List<ChatMessage>) {
        // Membership, not equality: an incoming message may arrive on any of a forked
        // group's sibling rooms, all of which belong to the same open thread.
        if (_state.value.open?.guids?.contains(convoGuid) != true) return
        openRaw = transform(openRaw)
        val folded = foldReactions(openRaw)
        _state.update { it.copy(messages = folded) }
    }

    /**
     * Folds tapback messages onto their targets: a reaction message isn't shown as
     * its own row but attached to the message it targets as a [Reaction]. A reactor
     * holds at most one tapback per message, so we key by (target, reactor) and let
     * the latest add win — a removal (3000s) clears it. Group-event rows we can't
     * describe (an itemType with no [GroupEvent] mapping — e.g. FaceTime call
     * markers) are dropped here too: they have no text and would render as blank
     * turns. Output is sorted by date.
     */
    private fun foldReactions(raw: List<ChatMessage>): List<ChatMessage> {
        val rows = raw.filterNot { it.isGroupEvent && it.groupEvent == null }
        if (rows.none { it.isReaction }) return rows.sortedBy { it.date }
        val active = LinkedHashMap<Pair<String, String>, Reaction?>()
        for (r in rows.filter { it.isReaction }.sortedBy { it.date }) {
            val target = r.reactionTargetGuid ?: continue
            val type = r.reactionType ?: continue
            val reactorKey = if (r.fromMe) "me" else (r.sender ?: "?")
            active[target to reactorKey] = if (r.isReactionRemoval) null else Reaction(type, r.fromMe, r.sender)
        }
        val byTarget = HashMap<String, MutableList<Reaction>>()
        for ((key, reaction) in active) {
            if (reaction != null) byTarget.getOrPut(key.first) { mutableListOf() }.add(reaction)
        }
        return rows.asSequence()
            .filterNot { it.isReaction }
            .map { m -> byTarget[m.guid]?.let { m.copy(reactions = it) } ?: m }
            .sortedBy { it.date }
            .toList()
    }

    // ---- Sending ----------------------------------------------------------

    /** The send method for text/attachments: the Private API when it's live, else
     *  AppleScript. The Private API is more capable (it sends by DB identity, so it
     *  handles any room of a forked group); the AppleScript path is pickier about the
     *  room guid — see [sendTarget]. */
    private fun sendMethod() = if (_state.value.privateApi) "private-api" else "apple-script"

    /**
     * The room guids to try when sending [convo], best first. The Private API resolves
     * a chat by its DB identity, so the primary (`convo.guid`) alone is enough.
     * AppleScript is pickier — its `chat id "…"` lookup can't resolve a *dead* room of
     * a forked group (it throws -1728 "Can't get chat id", which sends the server into
     * the DM-only fallback script that rejects groups: "Can't use the send message
     * (fallback) script to text a group chat!"). The live sibling resolves and delivers
     * fine. So for AppleScript we hand back *every* sibling room and let [sendAcrossRooms]
     * try each until one delivers — the last room that delivered (cached in
     * [lastGoodRoom]) first, then UUID-form rooms ahead of `chat<number>` forms, since
     * those tend to resolve. A 1:1 or single-room group is just `[guid]`.
     */
    private fun sendTargets(convo: Conversation, method: String): List<String> {
        if (method != "apple-script") return listOf(convo.guid)
        val ordered = convo.guids.sortedBy { it.substringAfterLast(";").startsWith("chat") }
        val good = lastGoodRoom[convo.guid]?.takeIf { it in convo.guids } ?: return ordered
        return listOf(good) + ordered.filter { it != good }
    }

    /**
     * Sends via the first of [targets] that succeeds; records the winning room in
     * [lastGoodRoom] under [convoGuid] so the next send tries it first, and returns its
     * result. Rethrows the last error if all fail. Lets an AppleScript send fall through
     * a forked group's dead rooms to the live one. Safe against double-sending: the
     * dead-room failure (-1728) happens during chat resolution, before any message goes
     * out.
     */
    private fun <T> sendAcrossRooms(convoGuid: String, targets: List<String>, send: (String) -> T): T {
        var last: Throwable? = null
        for (guid in targets) {
            try {
                val result = send(guid)
                lastGoodRoom[convoGuid] = guid
                return result
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("no send targets")
    }

    /** A client-side guid for an optimistic message, swapped for the server echo's
     *  real guid on reconcile. The `temp-` prefix marks a not-yet-acked message. */
    private fun newTempGuid(prefix: String = "temp") =
        "$prefix-${System.currentTimeMillis()}-${(0..99999).random()}"

    /** Swaps the optimistic [tempGuid] row for the server's [sent] echo, deduping
     *  in case the socket echo already landed under the real guid. */
    private fun reconcileEcho(convoGuid: String, tempGuid: String, sent: ChatMessage) {
        updateOpenThread(convoGuid) { list ->
            list.map { if (it.guid == tempGuid) sent else it }.distinctBy { it.guid }
        }
    }

    /** Drops the optimistic [tempGuid] row after a failed send and surfaces [error]. */
    private fun rollbackOptimistic(convoGuid: String, tempGuid: String, error: String) {
        updateOpenThread(convoGuid) { list -> list.filterNot { it.guid == tempGuid } }
        _state.update { it.copy(message = error) }
    }

    /**
     * Shared tail of [sendMessage]/[sendImage]: runs [call] against the first room
     * of [convo] that delivers (see [sendAcrossRooms]), then reconciles the
     * optimistic [tempGuid] row with the echo and bumps the conversation list —
     * or rolls the optimistic row back with [errorMessage]. Call off-main.
     */
    private fun performSend(
        convo: Conversation,
        tempGuid: String,
        errorMessage: String,
        call: (BlueBubblesApi, String, String) -> ChatMessage,
    ) {
        val client = api ?: return
        try {
            val method = sendMethod()
            val sent = sendAcrossRooms(convo.guid, sendTargets(convo, method)) { g ->
                call(client, g, method)
            }
            reconcileEcho(convo.guid, tempGuid, sent)
            bumpConversation(convo.guid, sent.previewText, sent.date, fromMe = true)
        } catch (t: Throwable) {
            rollbackOptimistic(convo.guid, tempGuid, errorMessage)
        }
    }

    /** Sends [text] into the open thread — as an inline reply to [replyToGuid]
     *  when given (Private-API only; ignored otherwise, and a not-yet-acked temp
     *  guid can't be replied to). */
    fun sendMessage(text: String, replyToGuid: String? = null) {
        val body = text.trim()
        val convo = _state.value.open ?: return
        if (body.isEmpty()) return
        finishTyping(convo.guid) // sending clears our typing bubble
        val reply = replyToGuid?.takeIf { _state.value.privateApi && !it.startsWith("temp-") }
        // Optimistic: show it immediately under a temp guid, then swap in the
        // server's echo (real guid) so the socket's new-message dedupes cleanly.
        val tempGuid = newTempGuid()
        val optimistic = ChatMessage(
            tempGuid, body, System.currentTimeMillis(), fromMe = true, sender = null,
            threadOriginatorGuid = reply,
        )
        updateOpenThread(convo.guid) { it + optimistic }
        _state.update { it.copy(message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            performSend(convo, tempGuid, "Couldn’t send") { client, g, method ->
                client.send(g, body, tempGuid, method, reply)
            }
        }
    }

    // ---- Tapbacks ---------------------------------------------------------

    /**
     * Sends a tapback onto [target] (or removes it, if [target] already carries
     * mine of [type] — long-pressing the same reaction toggles it off). Mirrors
     * [sendMessage]: an optimistic reaction message is folded in immediately, then
     * reconciled with the server's echo. Gated on [UiState.privateApi]; can't react
     * to a not-yet-acked optimistic message (no real guid to target).
     */
    fun sendReaction(target: ChatMessage, type: ReactionType) {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi) return
        if (target.guid.startsWith("temp-")) {
            _state.update { it.copy(message = "Still sending — try again in a moment") }
            return
        }
        val removing = target.reactions.firstOrNull { it.fromMe }?.type == type
        val apiValue = if (removing) "-${type.apiValue}" else type.apiValue
        val tempGuid = newTempGuid("temp-react")
        val optimistic = ChatMessage(
            guid = tempGuid,
            text = "",
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            associatedMessageGuid = target.guid,
            associatedMessageType = apiValue, // "love" or "-love"
        )
        updateOpenThread(convo.guid) { it + optimistic }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val sent = client.react(convo.guid, target.guid, apiValue)
                reconcileEcho(convo.guid, tempGuid, sent)
            } catch (t: Throwable) {
                rollbackOptimistic(convo.guid, tempGuid, "Couldn’t react")
            }
        }
    }

    // ---- Group management (Private API) ------------------------------------

    /**
     * Renames the open group — across *all* its rooms (best-effort), so a forked
     * group's dead siblings keep the same name and the name+participants merge key
     * holds instead of splitting the row. Succeeds if any room renamed; the
     * `group-name-change` socket echo and the refresh reconcile the rest.
     */
    fun renameGroup(name: String) {
        val convo = _state.value.open ?: return
        val newName = name.trim()
        if (!_state.value.privateApi || !convo.isGroup || newName.isEmpty()) return
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val results = convo.guids.map { g -> runCatching { client.renameChat(g, newName) } }
            if (results.none { it.isSuccess }) {
                _state.update { it.copy(message = "Couldn’t rename") }
                return@launch
            }
            _state.update { s ->
                s.copy(
                    open = s.open?.takeIf { it.guid == convo.guid }?.copy(displayName = newName) ?: s.open,
                    conversations = s.conversations.map { c ->
                        if (c.guid == convo.guid) c.copy(displayName = newName) else c
                    },
                )
            }
            loadConversations()
        }
    }

    /** Adds [address] to the open group. iMessage may fork the group into a new
     *  room for the new membership — the refresh reflects whatever it did. */
    fun addMember(address: String) {
        val convo = _state.value.open ?: return
        val addr = address.trim()
        if (!_state.value.privateApi || !convo.isGroup || addr.isEmpty()) return
        val client = api ?: return
        _state.update { it.copy(message = "Adding…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.addParticipant(convo.guid, addr)
                _state.update { s ->
                    s.copy(
                        open = s.open?.takeIf { it.guid == convo.guid }
                            ?.let { it.copy(participants = it.participants + addr) } ?: s.open,
                        message = null,
                    )
                }
                loadConversations()
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t add — are they on iMessage?") }
            }
        }
    }

    /** Removes [address] from the open group. */
    fun removeMember(address: String) {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi || !convo.isGroup) return
        val client = api ?: return
        _state.update { it.copy(message = "Removing…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                client.removeParticipant(convo.guid, address)
                _state.update { s ->
                    s.copy(
                        open = s.open?.takeIf { it.guid == convo.guid }
                            ?.let { o -> o.copy(participants = o.participants.filterNot { it == address }) }
                            ?: s.open,
                        message = null,
                    )
                }
                loadConversations()
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t remove them") }
            }
        }
    }

    /** Leaves the open group (every room of a forked group, best-effort), then
     *  drops back to the list. iMessage refuses on too-small groups — that
     *  surfaces as the failure message. */
    fun leaveGroup() {
        val convo = _state.value.open ?: return
        if (!_state.value.privateApi || !convo.isGroup) return
        val client = api ?: return
        _state.update { it.copy(message = "Leaving…") }
        viewModelScope.launch(Dispatchers.IO) {
            val results = convo.guids.map { g -> runCatching { client.leaveChat(g) } }
            if (results.none { it.isSuccess }) {
                _state.update { it.copy(message = "Couldn’t leave the conversation") }
                return@launch
            }
            closeThread()
            _state.update { it.copy(message = null) }
            loadConversations()
        }
    }

    /** Whether [address] can receive iMessages, delivered via [onResult] (skipped
     *  entirely when the Private API is down — the check needs it — or on a failed
     *  call, so "unknown" never blocks anyone). */
    fun checkIMessage(address: String, onResult: (Boolean) -> Unit) {
        if (!_state.value.privateApi) return
        val client = api ?: return
        viewModelScope.launch {
            val available = withContext(Dispatchers.IO) {
                runCatching { client.iMessageAvailable(address) }.getOrNull()
            }
            if (available != null) onResult(available)
        }
    }

    // ---- Attachments ------------------------------------------------------

    /** Decoded inline image for [attachment] (downloaded + cached on first use),
     *  or null if it isn't an image / can't be fetched. Called from the thread UI. */
    suspend fun loadImage(attachment: Attachment): ImageBitmap? {
        val client = api ?: return null
        return Attachments.image(app, client, attachment)
    }

    /**
     * Opens a non-image attachment: downloads it to a FileProvider-shared cache file,
     * then hands off to an external app via `ACTION_VIEW`. Falls back to a share
     * chooser, then a message if nothing on the (minimal) device can handle it.
     */
    fun openAttachment(attachment: Attachment) {
        val client = api ?: return
        _state.update { it.copy(message = "Downloading…") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(app.cacheDir, "shared").apply { mkdirs() }
                val safe = (attachment.transferName ?: attachment.guid)
                    .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { attachment.guid }
                val dest = File(dir, safe)
                if (!dest.exists() || dest.length() == 0L) client.downloadAttachment(attachment.guid, dest)
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", dest)
                val mime = attachment.mimeType ?: "application/octet-stream"
                _state.update { it.copy(message = null) }
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    app.startActivity(view)
                } catch (e: ActivityNotFoundException) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, "Open with")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { app.startActivity(chooser) }
                        .onFailure { _state.update { s -> s.copy(message = "No app can open this file") } }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Couldn’t download attachment") }
            }
        }
    }

    /**
     * Sends photos chosen in [com.gios.lightchat.ui.PhotoPickerScreen], as separate
     * attachments, in the order they were picked. One coroutine rather than one per
     * photo: iMessage has no concept of a batch, so these are N sends, and letting
     * them race would land them out of order in the thread.
     */
    fun sendImageFiles(files: List<File>) {
        val convo = _state.value.open ?: return
        if (files.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            for (file in files) {
                sendPicked(convo, readPickedImage(file) ?: continue)
            }
        }
    }

    /**
     * Shared body of the image sends. Mirrors [sendMessage]: an optimistic bubble goes
     * up immediately — the photo's bytes are seeded into the image cache under the temp
     * guid, so the normal loader renders them with no round trip — then the server's
     * echo swaps in under the real guid and the socket dedupes.
     */
    private fun sendPicked(convo: Conversation, img: PickedImage) {
        val tempGuid = newTempGuid()
        // Seed the cache so the optimistic bubble renders the local image.
        Attachments.cacheLocal(app, tempGuid, img.bytes)
        val optimistic = ChatMessage(
            guid = tempGuid,
            text = ChatMessage.ATTACHMENT_PLACEHOLDER,
            date = System.currentTimeMillis(),
            fromMe = true,
            sender = null,
            attachments = listOf(Attachment(tempGuid, img.mime, img.name, width = 0, height = 0)),
        )
        updateOpenThread(convo.guid) { it + optimistic }
        _state.update { it.copy(message = null) }
        // Blocking, deliberately: sendImageFiles loops over this on one IO coroutine
        // so several photos land in the thread in the order they were picked.
        performSend(convo, tempGuid, "Couldn’t send image") { client, g, method ->
            client.sendAttachment(g, img.bytes, img.name, img.mime, tempGuid, method)
        }
    }

    /** A picked image's bytes plus the metadata a send needs. */
    private class PickedImage(val bytes: ByteArray, val mime: String, val name: String)

    /** Reads a photo straight off disk — the picker hands us [java.io.File]s from
     *  [Gallery], not content URIs, so there is no provider to ask for the type or
     *  the display name. */
    private fun readPickedImage(file: File): PickedImage? {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || bytes.isEmpty()) {
            _state.update { it.copy(message = "Couldn’t read that photo") }
            return null
        }
        return PickedImage(bytes, mimeForExtension(file.extension), file.name)
    }

    private fun mimeForExtension(extension: String): String = when (extension.lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic" -> "image/heic"
        "heif" -> "image/heif"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

    /**
     * Sends a picked image as the first message of a *new* 1:1. There's no chat
     * guid yet, so we construct the canonical BlueBubbles 1:1 guid
     * (`iMessage;-;<handle>`) — sending an attachment to it creates the chat
     * server-side — then drop into the thread and refresh the list. The address is
     * normalized to the E.164 handle iMessage keys its guids by (`newChat`'s
     * AppleScript resolves loose addresses for text, but a constructed guid can't).
     */
    fun sendNewImage(address: String, file: File) {
        val addr = address.trim()
        if (addr.isEmpty()) return
        _state.update { it.copy(composingNew = false, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            val img = readPickedImage(file) ?: return@launch
            val handle = imessageHandle(addr)
            val guid = "iMessage;-;$handle"
            try {
                client.sendAttachment(guid, img.bytes, img.name, img.mime, newTempGuid(), sendMethod())
                messageCache.remove(guid)
                val convo = Conversation(
                    guid = guid,
                    displayName = "",
                    participants = listOf(handle),
                    isGroup = false,
                    lastText = "[Photo]",
                    lastDate = System.currentTimeMillis(),
                    lastFromMe = true,
                )
                _state.update { it.copy(message = null) }
                open(convo)   // land in the new thread (fetch pulls the sent image)
                refresh()     // and pull it into the conversation list
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "Couldn’t send image") }
            }
        }
    }

    /**
     * Photographs shared in from another app, optionally already addressed.
     *
     * With an address this is the whole point of the feature: Roll asked who the photograph
     * was for, so there is nothing left to choose and the send goes straight out — the thread
     * opens with the picture already in it rather than opening a picker the user has just
     * used.
     *
     * Without one, they are held and the user is put on the conversation list to pick a
     * thread. Only a share from a chooser that could not name the recipient reaches that
     * branch, and guessing would be worse than asking.
     *
     * Sequential rather than parallel, like [sendImageFiles]: iMessage has no batch send and
     * racing several attachments lands them out of order.
     */
    fun receiveShared(address: String, files: List<File>) {
        if (files.isEmpty()) return
        if (address.isBlank()) {
            pendingShared = files
            _state.update {
                it.copy(
                    open = null,
                    composingNew = false,
                    message = if (files.size == 1) {
                        "Open a chat to send the photo"
                    } else {
                        "Open a chat to send ${files.size} photos"
                    },
                )
            }
            return
        }
        _state.update { it.copy(composingNew = false, open = null, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            val handle = imessageHandle(address)
            // Constructed rather than looked up, exactly as sendNewImage does: a 1:1 chat's
            // guid *is* its handle, so this addresses an existing thread and creates one that
            // doesn't exist without needing to know which case it is.
            val guid = "iMessage;-;$handle"
            var sent = 0
            for (file in files) {
                val img = readPickedImage(file) ?: continue
                val ok = runCatching {
                    client.sendAttachment(guid, img.bytes, img.name, img.mime, newTempGuid(), sendMethod())
                }.isSuccess
                if (ok) sent++
                // The copy in our cache has served its purpose either way; leaving it means
                // every shared photo stays on the phone twice.
                runCatching { file.delete() }
            }
            if (sent == 0) {
                _state.update { it.copy(message = "Couldn’t send that photo") }
                return@launch
            }
            messageCache.remove(guid)
            val convo = Conversation(
                guid = guid,
                displayName = "",
                participants = listOf(handle),
                isGroup = false,
                lastText = if (sent == 1) "[Photo]" else "[$sent Photos]",
                lastDate = System.currentTimeMillis(),
                lastFromMe = true,
            )
            _state.update { it.copy(message = null) }
            open(convo)
            refresh()
        }
    }

    /**
     * Photographs shared in without a recipient, waiting for a thread to be opened.
     *
     * In memory only: a share the user abandons should not still be pending tomorrow, and the
     * files are in the cache directory, which the system may clear whenever it likes.
     */
    private var pendingShared: List<File> = emptyList()

    /** Called from [open]: if a share is waiting, the thread just opened is its destination. */
    private fun flushPendingShared() {
        val waiting = pendingShared
        if (waiting.isEmpty()) return
        pendingShared = emptyList()
        sendImageFiles(waiting)
    }

    /** Normalizes a picked address to the E.164 (or lowercased email) handle that
     *  iMessage keys 1:1 chat guids by. US-centric on the country code, matching the
     *  single personal account this app serves. */
    private fun imessageHandle(address: String): String {
        val a = address.trim()
        if (a.contains("@")) return a.lowercase()
        val digits = a.filter { it.isDigit() }
        return when {
            a.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> a
        }
    }

    // ---- New message ------------------------------------------------------

    fun startNewMessage() = _state.update { it.copy(composingNew = true, message = null) }

    fun cancelNewMessage() = _state.update { it.copy(composingNew = false, message = null) }

    /**
     * Starts a fresh chat with [addresses] by sending [text], then opens it. One
     * address is a 1:1 (AppleScript); two or more form a group, which the server
     * only creates over the Private API — so a group send is gated on
     * `state.privateApi` (the picker also hides the option, this is the backstop).
     * The group's guid is server-assigned, so we open on whatever `newChat` returns.
     */
    fun sendNewMessage(addresses: List<String>, text: String) {
        val addrs = addresses.map { it.trim() }.filter { it.isNotEmpty() }
        val body = text.trim()
        if (addrs.isEmpty() || body.isEmpty()) return
        val isGroup = addrs.size > 1
        if (isGroup && !_state.value.privateApi) {
            _state.update { it.copy(message = "Group messaging needs the server’s Private API") }
            return
        }
        _state.update { it.copy(composingNew = false, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val guid = client.newChat(addrs, body)
                messageCache.remove(guid)
                val convo = Conversation(
                    guid = guid,
                    displayName = "",
                    participants = addrs,
                    isGroup = isGroup,
                    lastText = body,
                    lastDate = System.currentTimeMillis(),
                    lastFromMe = true,
                )
                _state.update { it.copy(message = null) }
                open(convo)   // land the user in the new thread
                refresh()     // and pull it into the conversation list
            } catch (t: Throwable) {
                _state.update { it.copy(message = t.message ?: "Couldn’t start the message") }
            }
        }
    }

    // ---- Live feed (socket) ----------------------------------------------

    private fun observeSocket() {
        viewModelScope.launch {
            SocketBus.incoming.collect { applyIncoming(it) }
        }
        viewModelScope.launch {
            SocketBus.typing.collect { applyTyping(it) }
        }
        viewModelScope.launch {
            SocketBus.readStatus.collect { applyReadStatus(it) }
        }
    }

    // ---- Typing indicators ------------------------------------------------

    private var typingExpiryJob: Job? = null      // clears a stale "typing" if no refresh
    private var typingSent = false                // whether we've told the server we're typing
    private var typingStopJob: Job? = null        // fires the auto-stop after a pause

    /** Receiving: fold a typing change into state. A "stopped" event can be missed,
     *  so a "typing" auto-expires after [TYPING_EXPIRY_MS] without a refresh (the
     *  server re-emits ~every 5s while typing continues). */
    private fun applyTyping(event: TypingEvent) {
        typingExpiryJob?.cancel()
        if (event.typing) {
            _state.update { it.copy(typingChatGuid = event.chatGuid) }
            typingExpiryJob = viewModelScope.launch {
                delay(TYPING_EXPIRY_MS)
                _state.update { if (it.typingChatGuid == event.chatGuid) it.copy(typingChatGuid = null) else it }
            }
        } else {
            _state.update { if (it.typingChatGuid == event.chatGuid) it.copy(typingChatGuid = null) else it }
        }
    }

    /** Sending: the open thread's compose text changed. Tells the server we're
     *  typing on the first keystroke and schedules an auto-stop after a pause; an
     *  empty field stops immediately. No-op unless the Private API is live. */
    fun onComposeTextChanged(text: String) {
        if (!_state.value.privateApi) return
        val guid = _state.value.open?.guid ?: return
        if (text.isBlank()) {
            typingStopJob?.cancel()
            stopTypingNow(guid)
            return
        }
        val client = api ?: return
        if (!typingSent) {
            typingSent = true
            viewModelScope.launch(Dispatchers.IO) { runCatching { client.startTyping(guid) } }
        }
        typingStopJob?.cancel()
        typingStopJob = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            stopTypingNow(guid)
        }
    }

    private fun stopTypingNow(guid: String) {
        if (!typingSent) return
        typingSent = false
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { client.stopTyping(guid) } }
    }

    /** Cancel a pending auto-stop and clear our typing state — on send or close. */
    private fun finishTyping(guid: String) {
        typingStopJob?.cancel()
        stopTypingNow(guid)
    }

    private fun applyIncoming(incoming: IncomingMessage) {
        val known = _state.value.conversations.any { incoming.chatGuid in it.guids }
        // Looking right at the thread — don't flag unread, and mark read below.
        val viewing = _state.value.open?.guids?.contains(incoming.chatGuid) == true && AppForeground.active
        // A genuinely new message (or tapback) from someone else, not on screen →
        // the row goes unread. Group events bump recency but aren't unread-worthy
        // (they also never get a dateRead, so the load derivation skips them too).
        val flagUnread = incoming.isNew && !incoming.message.fromMe &&
            !incoming.message.isGroupEvent && !viewing
        _state.update { s ->
            val convos = s.conversations.map { c ->
                if (incoming.chatGuid in c.guids) {
                    when {
                        // A group event (rename, member change) bumps recency but
                        // isn't speech — the text preview keeps the newest real message.
                        incoming.message.isGroupEvent -> c.copy(
                            lastDate = maxOf(c.lastDate, incoming.message.date),
                        )
                        // A tapback bumps recency and surfaces as "Liz loved an image"
                        // ([lastReaction]); a removal clears that overlay; a normal message
                        // updates the text preview and clears any reaction overlay.
                        incoming.message.isReaction -> c.copy(
                            lastDate = incoming.message.date,
                            lastReaction = incoming.message.reactionPreview(::cachedMessage),
                            unread = c.unread || flagUnread,
                        )
                        else -> c.copy(
                            lastText = incoming.message.previewText,
                            lastDate = incoming.message.date,
                            lastFromMe = incoming.message.fromMe,
                            lastReaction = null,
                            unread = c.unread || flagUnread,
                        )
                    }
                } else {
                    c
                }
            }.sortedByDescending { it.lastDate }
            s.copy(conversations = convos)
        }
        // A rename should reflect immediately in the open thread's title; the list
        // row's stored displayName comes from the refresh below.
        if (incoming.message.groupEvent == GroupEvent.RENAMED) {
            _state.update { s ->
                val open = s.open
                if (open != null && incoming.chatGuid in open.guids) {
                    s.copy(open = open.copy(displayName = incoming.chatDisplayName))
                } else {
                    s
                }
            }
            refresh()
        }
        // Fold the message into the open thread's raw list (a tapback lands on its
        // target; a normal message appends). foldReactions re-runs in updateOpenThread.
        updateOpenThread(incoming.chatGuid) { mergeRaw(it, incoming.message) }
        // If it's an incoming message in the thread you're looking at, mark the chat
        // read so the unread clears on your other devices too — and record the clear
        // locally so a refresh can't resurrect the dot before the server catches up.
        if (!incoming.message.fromMe && viewing) {
            markReadIfPrivate(incoming.chatGuid)
            _state.value.conversations.firstOrNull { incoming.chatGuid in it.guids }
                ?.let { clearUnread(it.guid) }
        }
        // **Persist it.** Without this the store would drift from what is on screen, and the
        // next launch would read a list missing everything that arrived while the app was
        // open — then fetch it all again. The message body is only kept for threads that
        // have actually been opened; for the rest the list row is all the app shows.
        persistIncoming(incoming)
        // A message for a chat not currently in the list (e.g. a brand-new
        // conversation) — pull the list again so it appears with full metadata.
        if (!known) refresh()
    }

    /** Writes a live message and its list row through to the store, off the main thread. */
    private fun persistIncoming(incoming: IncomingMessage) {
        val rows = _state.value.conversations.filter { incoming.chatGuid in it.guids }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (rows.isNotEmpty()) store.putChats(rows)
                if (incoming.raw.isNotBlank() && store.isThreadLoaded(incoming.chatGuid)) {
                    store.putMessages(
                        listOf(MessageStore.Row(incoming.chatGuid, incoming.message, incoming.raw)),
                    )
                    store.trim(incoming.chatGuid)
                }
            }
        }
    }

    /** A `chat-read-status-changed` from the socket: the chat was read somewhere
     *  (another device, or our own markRead echoing back) — drop its unread dot. */
    private fun applyReadStatus(event: ReadStatusEvent) {
        if (!event.read) return
        _state.value.conversations.firstOrNull { event.chatGuid in it.guids }
            ?.let { clearUnread(it.guid) }
    }

    /** Clears [convoGuid]'s unread marker in the list and records it in
     *  [clearedUnread] so the next refresh can't resurrect it (the server's
     *  dateRead stamp can lag our markRead). */
    private fun clearUnread(convoGuid: String) {
        val convo = _state.value.conversations.firstOrNull { it.guid == convoGuid } ?: return
        clearedUnread[convoGuid] = maxOf(clearedUnread[convoGuid] ?: 0L, convo.lastDate)
        if (!convo.unread) return
        _state.update { s ->
            s.copy(conversations = s.conversations.map { if (it.guid == convoGuid) it.copy(unread = false) else it })
        }
        // **Written through, not just to state.** The delta filters on creation date, so a
        // message whose `dateRead` is stamped afterwards is never re-fetched and the stored
        // row keeps `unread = true` for good. The old full sweep re-derived it from
        // `dateRead` every launch and so got away with an in-memory clear; this one would
        // resurrect the dot on every process restart, on every chat that has since gone
        // quiet.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.putChats(listOf(convo.copy(unread = false))) }
        }
    }

    /**
     * Clears every unread dot at once (Settings → Mark all as read). Returns what to tell
     * the user, because the two halves can disagree and `state.message` is the wrong place
     * for it — the conversation list only renders that when the list is *empty*, so the
     * text would be invisible here and then turn up floating in the next thread opened.
     *
     * The dots go immediately and are recorded in [clearedUnread] so the next refresh
     * can't resurrect them while the server's `dateRead` catches up — that part always
     * works. Sending the actual read receipts needs the Private API, and without it the
     * Mac still thinks they're unread, so say so rather than implying more happened.
     */
    fun markAllRead(): String {
        val unread = _state.value.conversations.filter { it.unread }
        if (unread.isEmpty()) return "Nothing unread"
        val cleared = unread.map { it.guid }.toSet()
        unread.forEach { clearedUnread[it.guid] = maxOf(clearedUnread[it.guid] ?: 0L, it.lastDate) }
        // Keyed on the captured set, not on `it.unread`: update's lambda re-runs on CAS
        // contention, and a message arriving in that window would have its dot cleared
        // here without a clearedUnread entry or a receipt — so the next refresh would
        // bring it straight back.
        _state.update { s ->
            s.copy(conversations = s.conversations.map { if (it.guid in cleared) it.copy(unread = false) else it })
        }
        Notifications.clear(app)
        // Every room, not just the primary guid: a forked group spans several.
        unread.flatMap { it.guids }.forEach { markReadIfPrivate(it) }
        val n = cleared.size
        val what = if (n == 1) "1 conversation" else "$n conversations"
        return if (_state.value.privateApi) {
            "Marked $what read"
        } else {
            "Cleared $what here — the Private API is off, so the Mac still shows them unread"
        }
    }

    /** Marks [chatGuid] read on the server (best-effort, off-main), but only when
     *  the Private API is live — it's the only path that can send a read receipt. */
    private fun markReadIfPrivate(chatGuid: String) {
        if (!_state.value.privateApi) return
        val client = api ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { client.markRead(chatGuid) } }
    }

    private fun mergeRaw(list: List<ChatMessage>, m: ChatMessage): List<ChatMessage> {
        // Match by guid, or — for the socket echo of our own send — by the tempGuid the
        // server echoes back, since the optimistic bubble still carries it as its guid.
        // Without the latter the echo (real guid) would render as a second row until the
        // HTTP send call returns and swaps the temp guid in.
        val idx = list.indexOfFirst { it.guid == m.guid || (m.tempGuid != null && it.guid == m.tempGuid) }
        return if (idx >= 0) list.toMutableList().also { it[idx] = m } else list + m
    }

    /** Looks up a message by guid in whatever's cached (the open thread, or any
     *  previously-opened thread), so a live tapback can describe its target. Null
     *  when the target isn't loaded — the preview then reads "a message". */
    private fun cachedMessage(guid: String): ChatMessage? {
        openRaw.firstOrNull { it.guid == guid }?.let { return it }
        for (list in messageCache.values) list.firstOrNull { it.guid == guid }?.let { return it }
        return null
    }

    private fun bumpConversation(guid: String, text: String, date: Long, fromMe: Boolean) {
        _state.update { s ->
            val convos = s.conversations.map { c ->
                // A real message (this is only called for sends) clears any reaction overlay.
                if (guid in c.guids) c.copy(lastText = text, lastDate = date, lastFromMe = fromMe, lastReaction = null) else c
            }.sortedByDescending { it.lastDate }
            s.copy(conversations = convos)
        }
    }

    // ---- Service lifecycle ------------------------------------------------

    private fun startSocket() {
        app.startForegroundService(Intent(app, SocketService::class.java))
    }

    private fun stopSocket() {
        app.stopService(Intent(app, SocketService::class.java))
    }

    // ---- Errors / sign out ------------------------------------------------

    private fun handleError(t: Throwable) {
        if (t is ApiException && t.isAuthError) {
            signOutInternal("Password rejected")
            return
        }
        _state.update { it.copy(status = Status.Error, message = t.message ?: "Something went wrong") }
    }

    fun signOut() = signOutInternal(null)

    /** The store holds the account's messages, so it goes when the password does. Off the
     *  main thread: three unbounded DELETEs over a database that may have run for months. */
    private fun clearStore() {
        viewModelScope.launch(Dispatchers.IO) { runCatching { store.clear() } }
    }

    private fun signOutInternal(message: String?) {
        loadJob?.cancel()
        threadJob?.cancel()
        api = null
        sync = null
        stopSocket()
        Store.signOut(app)
        clearStore()
        messageCache.clear()
        _state.value = UiState(isConfigured = false, message = message)
    }

    override fun onCleared() {
        loadJob?.cancel()
        threadJob?.cancel()
        super.onCleared()
    }
}
