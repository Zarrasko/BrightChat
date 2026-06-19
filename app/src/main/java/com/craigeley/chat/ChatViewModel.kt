package com.craigeley.chat

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.craigeley.chat.api.ApiException
import com.craigeley.chat.api.BlueBubblesApi
import com.craigeley.chat.api.Store
import com.craigeley.chat.socket.SocketBus
import com.craigeley.chat.socket.SocketService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Status { Idle, Loading, Ready, Error }

data class UiState(
    val hasPassword: Boolean,
    val status: Status = Status.Idle,
    val conversations: List<Conversation> = emptyList(),
    val open: Conversation? = null,            // the currently-open thread, if any
    val messages: List<ChatMessage> = emptyList(),
    val threadLoading: Boolean = false,
    val contacts: Contacts = Contacts(),       // address → name, from the server's address book
    val contactList: List<Contact> = emptyList(), // searchable recipients for a new message
    val composingNew: Boolean = false,         // the "New message" compose screen is open
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

    private var api: BlueBubblesApi? =
        Store.password(application)?.takeIf { it.isNotBlank() }?.let { BlueBubblesApi(Store.BASE_URL, it) }

    private val _state = MutableStateFlow(UiState(hasPassword = api != null))
    val state: StateFlow<UiState> = _state

    private var loadJob: Job? = null
    private var threadJob: Job? = null

    // The address book is small (hundreds of contacts) and changes rarely, so we
    // fetch it once per session alongside the first conversation load and cache it.
    private var contacts = Contacts()
    private var contactList = emptyList<Contact>()
    private var contactsLoaded = false

    // Per-conversation message cache (session-lived). Reopening a thread shows the
    // cached messages instantly while a fresh fetch refreshes in the background —
    // no "Loading…" flash. Snapshotted on close so live updates persist.
    private val messageCache = HashMap<String, List<ChatMessage>>()

    init {
        observeSocket()
        if (api != null) {
            refresh()
            startSocket()
        }
    }

    // ---- Setup ------------------------------------------------------------

    fun savePassword(value: String) {
        val pw = value.trim()
        if (pw.isEmpty()) return
        _state.update { it.copy(status = Status.Loading, message = "Connecting…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = BlueBubblesApi(Store.BASE_URL, pw)
            val ok = runCatching { client.validate() }.getOrDefault(false)
            if (ok) {
                Store.setPassword(app, pw)
                api = client
                _state.update { it.copy(hasPassword = true, message = null) }
                loadConversations()
                startSocket()
            } else {
                _state.update {
                    it.copy(status = Status.Error, message = "Couldn’t reach the server — check the password")
                }
            }
        }
    }

    // ---- Conversation list ------------------------------------------------

    fun refresh() {
        if (api == null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) { loadConversations() }
    }

    private suspend fun loadConversations() {
        val client = api ?: return
        _state.update { it.copy(status = Status.Loading, message = null) }
        try {
            val convos = client.conversations().sortedByDescending { it.lastDate }
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
                    message = null,
                )
            }
        } catch (t: Throwable) {
            handleError(t)
        }
    }

    // ---- One thread -------------------------------------------------------

    fun open(conversation: Conversation) {
        val cached = messageCache[conversation.guid]
        _state.update {
            it.copy(open = conversation, messages = cached ?: emptyList(), threadLoading = cached == null)
        }
        threadJob?.cancel()
        threadJob = viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val msgs = client.messages(conversation.guid).sortedBy { it.date }
                messageCache[conversation.guid] = msgs
                _state.update { s ->
                    if (s.open?.guid == conversation.guid) s.copy(messages = msgs, threadLoading = false) else s
                }
            } catch (t: Throwable) {
                _state.update { if (it.open?.guid == conversation.guid) it.copy(threadLoading = false) else it }
                handleError(t)
            }
        }
    }

    fun closeThread() {
        // Snapshot what's on screen (incl. live updates) so reopening is instant.
        _state.value.open?.let { messageCache[it.guid] = _state.value.messages }
        threadJob?.cancel()
        _state.update { it.copy(open = null, messages = emptyList(), threadLoading = false) }
    }

    // ---- Sending ----------------------------------------------------------

    fun sendMessage(text: String) {
        val body = text.trim()
        val convo = _state.value.open ?: return
        if (body.isEmpty()) return
        // Optimistic: show it immediately under a temp guid, then swap in the
        // server's echo (real guid) so the socket's new-message dedupes cleanly.
        val tempGuid = "temp-${System.currentTimeMillis()}-${(0..99999).random()}"
        val optimistic = ChatMessage(tempGuid, body, System.currentTimeMillis(), fromMe = true, sender = null)
        _state.update { it.copy(messages = (it.messages + optimistic).sortedBy { m -> m.date }, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val sent = client.send(convo.guid, body, tempGuid)
                _state.update { s ->
                    val replaced = s.messages
                        .map { if (it.guid == tempGuid) sent else it }
                        .distinctBy { it.guid }
                        .sortedBy { m -> m.date }
                    s.copy(messages = replaced)
                }
                bumpConversation(convo.guid, sent.text, sent.date, fromMe = true)
            } catch (t: Throwable) {
                _state.update { s ->
                    s.copy(messages = s.messages.filterNot { it.guid == tempGuid }, message = "Couldn’t send")
                }
            }
        }
    }

    // ---- Attachments ------------------------------------------------------

    /** Decoded inline image for [attachment] (downloaded + cached on first use),
     *  or null if it isn't an image / can't be fetched. Called from the thread UI. */
    suspend fun loadImage(attachment: Attachment): androidx.compose.ui.graphics.ImageBitmap? {
        val client = api ?: return null
        return Attachments.image(app, client, attachment)
    }

    /**
     * Sends a picked image into the open thread. Mirrors [sendMessage]: it shows an
     * optimistic bubble immediately — the picked bytes are seeded into the image
     * cache under the temp guid so the normal loader renders them without a round
     * trip — then swaps in the server's echo (real guid) so the socket dedupes.
     */
    fun sendImage(uri: android.net.Uri) {
        val convo = _state.value.open ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            val resolver = app.contentResolver
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                _state.update { it.copy(message = "Couldn’t read that image") }
                return@launch
            }
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val name = queryDisplayName(uri) ?: "image.jpg"
            val tempGuid = "temp-${System.currentTimeMillis()}-${(0..99999).random()}"
            // Seed the cache so the optimistic bubble renders the local image.
            Attachments.cacheLocal(app, tempGuid, bytes)
            val optimistic = ChatMessage(
                guid = tempGuid,
                text = ChatMessage.ATTACHMENT_PLACEHOLDER,
                date = System.currentTimeMillis(),
                fromMe = true,
                sender = null,
                attachments = listOf(Attachment(tempGuid, mime, name, width = 0, height = 0)),
            )
            _state.update { it.copy(messages = (it.messages + optimistic).sortedBy { m -> m.date }, message = null) }
            try {
                val sent = client.sendAttachment(convo.guid, bytes, name, mime, tempGuid)
                _state.update { s ->
                    val replaced = s.messages
                        .map { if (it.guid == tempGuid) sent else it }
                        .distinctBy { it.guid }
                        .sortedBy { m -> m.date }
                    s.copy(messages = replaced)
                }
                bumpConversation(convo.guid, sent.text, sent.date, fromMe = true)
            } catch (t: Throwable) {
                _state.update { s ->
                    s.copy(messages = s.messages.filterNot { it.guid == tempGuid }, message = "Couldn’t send image")
                }
            }
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String? =
        runCatching {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst() && it.columnCount > 0) it.getString(0) else null }
        }.getOrNull()

    // ---- New message ------------------------------------------------------

    fun startNewMessage() = _state.update { it.copy(composingNew = true, message = null) }

    fun cancelNewMessage() = _state.update { it.copy(composingNew = false, message = null) }

    /** Starts a fresh 1:1 chat with [address] by sending [text], then opens it. */
    fun sendNewMessage(address: String, text: String) {
        val addr = address.trim()
        val body = text.trim()
        if (addr.isEmpty() || body.isEmpty()) return
        _state.update { it.copy(composingNew = false, message = "Sending…") }
        viewModelScope.launch(Dispatchers.IO) {
            val client = api ?: return@launch
            try {
                val guid = client.newChat(addr, body)
                messageCache.remove(guid)
                val convo = Conversation(
                    guid = guid,
                    displayName = "",
                    participants = listOf(addr),
                    isGroup = false,
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
    }

    private fun applyIncoming(incoming: IncomingMessage) {
        val known = _state.value.conversations.any { it.guid == incoming.chatGuid }
        _state.update { s ->
            val convos = s.conversations.map { c ->
                if (c.guid == incoming.chatGuid) {
                    c.copy(
                        lastText = incoming.message.text,
                        lastDate = incoming.message.date,
                        lastFromMe = incoming.message.fromMe,
                    )
                } else {
                    c
                }
            }.sortedByDescending { it.lastDate }
            val messages = if (s.open?.guid == incoming.chatGuid) {
                mergeMessage(s.messages, incoming.message)
            } else {
                s.messages
            }
            s.copy(conversations = convos, messages = messages)
        }
        // A message for a chat not currently in the list (e.g. a brand-new
        // conversation) — pull the list again so it appears with full metadata.
        if (!known) refresh()
    }

    private fun mergeMessage(list: List<ChatMessage>, m: ChatMessage): List<ChatMessage> {
        val idx = list.indexOfFirst { it.guid == m.guid }
        val merged = if (idx >= 0) list.toMutableList().also { it[idx] = m } else list + m
        return merged.sortedBy { it.date }
    }

    private fun bumpConversation(guid: String, text: String, date: Long, fromMe: Boolean) {
        _state.update { s ->
            val convos = s.conversations.map { c ->
                if (c.guid == guid) c.copy(lastText = text, lastDate = date, lastFromMe = fromMe) else c
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

    private fun signOutInternal(message: String?) {
        loadJob?.cancel()
        threadJob?.cancel()
        api = null
        stopSocket()
        Store.signOut(app)
        _state.value = UiState(hasPassword = false, message = message)
    }

    override fun onCleared() {
        loadJob?.cancel()
        threadJob?.cancel()
        super.onCleared()
    }
}
