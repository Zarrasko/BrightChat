package com.gios.lightchat.socket

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.gios.lightchat.Contacts
import com.gios.lightchat.HeadsUp
import com.gios.lightchat.Notifications
import com.gios.lightchat.ReadStatusEvent
import com.gios.lightchat.TypingEvent
import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.api.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URLEncoder
import org.json.JSONObject

/**
 * Foreground service holding the one live Socket.IO connection to the BlueBubbles
 * server. This is the OpenBubbles replacement's core: with no Google Play push on
 * the device, instant delivery means keeping a socket open ourselves — but it's a
 * single lightweight connection, not a whole Flutter runtime.
 *
 * On `new-message`/`updated-message` it parses the payload (shared with
 * [BlueBubblesApi]), pushes it onto [SocketBus] for the live UI, and — when the
 * app isn't foreground — raises a notification.
 */
class SocketService : Service() {

    private var socket: Socket? = null
    private var api: BlueBubblesApi? = null

    /** Cancelled in [onDestroy]; used for the read-verification round trip. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        startForeground(
            Notifications.SERVICE_ID,
            Notifications.foregroundNotification(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
        )
        connect()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * The safety net under the live socket.
     *
     * With no Google push, instant delivery is a socket we hold open ourselves — and a
     * socket is not a guarantee. It can be silently wedged (connected as far as we know,
     * no events arriving), the Tailscale tunnel can drop while the screen is off, Doze can
     * freeze us, and the process can be killed outright. Any of those and a message simply
     * never arrives, with nothing to notice: this is the "sometimes it doesn't notify me"
     * failure.
     *
     * So every [WATCHDOG_MS] we reconnect if the socket says it's down, and re-pull the
     * list either way, alerting for anything past the watermark. Cheap — one REST call —
     * and it bounds how long a missed message can stay missed. What it can't cover is the
     * process being killed; `MainActivity.onStart` re-pulling is the backstop there.
     */
    private fun startWatchdog() {
        scope.launch {
            while (isActive) {
                delay(WATCHDOG_MS)
                val live = socket
                if (live == null || !live.connected()) {
                    Log.w(TAG, "socket down; reconnecting")
                    runCatching { live?.connect() }
                }
                catchUp()
            }
        }
    }

    /**
     * Pulls the conversation list and notifies for anything unread that arrived after
     * [Store.lastAlertedAt]. Notification only — no box, no screen wake: by the time this
     * finds a message it's minutes old, and lighting the panel for old news is the thing
     * we spent so long stopping. One buzz if it found anything at all.
     *
     * First run seeds the watermark without alerting, or the backlog would all arrive at
     * once the first time a build with this in it starts.
     */
    private suspend fun catchUp() {
        val client = client() ?: return
        val convos = runCatching { client.conversations(limit = 50) }.getOrNull() ?: return
        val watermark = Store.lastAlertedAt(this)
        val newest = convos.maxOfOrNull { it.lastDate } ?: return
        if (watermark == 0L) {
            Store.setLastAlertedAt(this, newest)
            return
        }
        if (newest <= watermark) return
        val missed = convos.filter { it.unread && !it.lastFromMe && it.lastDate > watermark }
        // Foregrounded: the list on screen is being refreshed anyway, and an alert for
        // something the user is looking at is noise. The watermark still moves.
        if (missed.isEmpty() || AppForeground.active) {
            Store.setLastAlertedAt(this, newest)
            return
        }
        Log.d(TAG, "catch-up found ${missed.size} missed")
        val contacts = contacts()
        for (convo in missed) {
            val title = convo.displayName.ifBlank {
                convo.participants.firstOrNull()?.let { contacts.name(it) ?: it } ?: "Message"
            }
            Notifications.post(this, title, convo.lastText, convo.guid)
        }
        // Advanced *after* posting, not before: if this dies partway the next poll retries,
        // and a retry is harmless — notification ids are per chat, so a repost replaces the
        // same row. Losing an alert is the failure that matters.
        Store.setLastAlertedAt(this, newest)
        HeadsUp.buzz(this)
    }

    private fun connect() {
        val password = Store.password(this) ?: run { stopSelf(); return }
        val baseUrl = Store.baseUrl(this) ?: run { stopSelf(); return }
        val opts = IO.Options().apply {
            transports = arrayOf("websocket") // server upgrades to ws anyway; skip polling
            query = "password=" + URLEncoder.encode(password, "UTF-8")
            reconnection = true
        }
        val s = runCatching { IO.socket(baseUrl, opts) }.getOrNull() ?: run { stopSelf(); return }
        socket = s
        s.on(Socket.EVENT_CONNECT, Emitter.Listener {
            Log.d(TAG, "socket connected")
            // A reconnect means we were off the air for some length of time; find out what
            // arrived while we were.
            scope.launch { catchUp() }
        })
        s.on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener { Log.w(TAG, "connect error: ${it.firstOrNull()}") })
        s.on("new-message", Emitter.Listener { onMessage(it, isNew = true) })
        s.on("updated-message", Emitter.Listener { onMessage(it, isNew = false) })
        // A send the Mac accepted but couldn't deliver (e.g. not an iMessage
        // address) — the payload is the message with its `error` set; merging it
        // flips the bubble to "Not delivered" instead of failing silently.
        s.on("message-send-error", Emitter.Listener { onMessage(it, isNew = false) })
        s.on("typing-indicator", Emitter.Listener { onTyping(it) })
        // The chat was read somewhere on the account (Mac, iPhone, or our own
        // markRead) — the server's chat.db poller reports it, no Private API needed.
        // Clears the list's unread marker and the chat's now-stale notification.
        s.on("chat-read-status-changed", Emitter.Listener { onReadStatus(it) })
        // Group-system changes arrive as their own event types but carry the same
        // serialized message payload (embedded chats included), so they route
        // through the normal message path — the thread shows them as event rows
        // and the list bumps. They never notify (see onMessage's isGroupEvent gate).
        for (event in listOf("group-name-change", "participant-added", "participant-removed", "participant-left")) {
            s.on(event, Emitter.Listener { onMessage(it, isNew = true) })
        }
        s.connect()
    }

    /**
     * A `chat-read-status-changed` event — `{ chatGuid, read }`. Bridged to the ViewModel
     * for the unread marker, and when read, dismisses the chat's alert so something
     * already read on another device doesn't linger here.
     *
     * The event carries no timestamp, and the server's chat.db poller will happily report
     * `read: true` describing the state *before* a message that has just arrived — a chat
     * you had open on this phone (so we marked it read) is the common way to get one. Left
     * alone, that stale event dismisses the notification for the reply you were waiting
     * for, seconds after it was posted. So a read is verified before it's acted on: ask
     * the server for the chat's newest message and only dismiss if that message really
     * does carry a `dateRead`.
     */
    private fun onReadStatus(args: Array<out Any?>?) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val guid = data.optString("chatGuid").takeIf { it.isNotBlank() } ?: return
        val read = data.optBoolean("read", false)
        SocketBus.readStatus.tryEmit(ReadStatusEvent(guid, read))
        if (!read) return
        // Off the socket thread: this makes a REST call.
        scope.launch {
            if (!newestIsRead(guid)) {
                Log.d(TAG, "stale read for $guid — newest message still unread, keeping the alert")
                return@launch
            }
            Notifications.clearChat(this@SocketService, listOf(guid))
            HeadsUp.cancel(guid)
        }
    }

    /**
     * Whether [chatGuid]'s newest incoming message has actually been read. False on any
     * failure: keeping an alert we can't justify dismissing is the safe direction.
     */
    private suspend fun newestIsRead(chatGuid: String): Boolean = withContext(Dispatchers.IO) {
        val client = client() ?: return@withContext false
        val newest = runCatching { client.messages(chatGuid, limit = 5) }
            .getOrNull()
            ?.filterNot { it.fromMe || it.isGroupEvent }
            ?.maxByOrNull { it.date }
            ?: return@withContext false
        newest.dateRead != 0L
    }

    /** Built on demand from the stored setup, and only for [newestIsRead] — the socket
     *  itself needs no client. */
    private fun client(): BlueBubblesApi? {
        api?.let { return it }
        val url = Store.baseUrl(this) ?: return null
        val password = Store.password(this) ?: return null
        return BlueBubblesApi(url, password).also { api = it }
    }

    /** A `typing-indicator` event — `{ display, guid }` — bridged to the ViewModel.
     *  No notification; it only matters for the open thread. */
    private fun onTyping(args: Array<out Any?>?) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val guid = data.optString("guid").takeIf { it.isNotBlank() } ?: return
        SocketBus.typing.tryEmit(TypingEvent(guid, data.optBoolean("display", false)))
    }

    private fun onMessage(args: Array<out Any?>?, isNew: Boolean) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val incoming = BlueBubblesApi.messageEvent(data, isNew) ?: return
        SocketBus.incoming.tryEmit(incoming)
        // Notify only for genuinely new incoming messages the user can't see —
        // not group events (renames etc.), whose `text` is empty.
        if (isNew && !incoming.message.fromMe && !incoming.message.isGroupEvent && !AppForeground.active) {
            // Prefer an explicit (group) chat name; otherwise resolve the sender's
            // address to a contact name from the persisted index, falling back to
            // the raw address. Read fresh so it reflects the latest address book.
            val title = incoming.chatDisplayName.ifBlank {
                val sender = incoming.message.sender
                sender?.let { contacts().name(it) ?: it } ?: "Message"
            }
            // The notification is the record — it stays in LightOS's list and feeds
            // LightGlance's dot. The box is the alert, and buzzes either way.
            Notifications.post(this, title, incoming.message.text, incoming.chatGuid)
            HeadsUp.show(
                this,
                title,
                incoming.message.text,
                incoming.chatGuid,
                // Stamped already: read on another device before the event even got here.
                alreadyRead = incoming.message.dateRead != 0L,
            )
        }
    }

    /** The persisted contact index. Reloaded per notification (infrequent — only
     *  background messages) so a name added while the service ran still resolves. */
    private fun contacts(): Contacts = Store.contacts(this)

    override fun onDestroy() {
        scope.cancel()
        socket?.disconnect()
        socket?.off()
        socket = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SocketService"

        /** How long a missed message can stay missed while the service is alive. */
        private const val WATCHDOG_MS = 5 * 60 * 1000L
    }
}
