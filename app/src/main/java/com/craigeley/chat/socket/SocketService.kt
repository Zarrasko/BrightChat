package com.craigeley.chat.socket

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.craigeley.chat.Contacts
import com.craigeley.chat.Notifications
import com.craigeley.chat.api.BlueBubblesApi
import com.craigeley.chat.api.Store
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun connect() {
        val password = Store.password(this) ?: run { stopSelf(); return }
        val opts = IO.Options().apply {
            transports = arrayOf("websocket") // server upgrades to ws anyway; skip polling
            query = "password=" + URLEncoder.encode(password, "UTF-8")
            reconnection = true
        }
        val s = runCatching { IO.socket(Store.BASE_URL, opts) }.getOrNull() ?: run { stopSelf(); return }
        socket = s
        s.on(Socket.EVENT_CONNECT, Emitter.Listener { Log.d(TAG, "socket connected") })
        s.on(Socket.EVENT_CONNECT_ERROR, Emitter.Listener { Log.w(TAG, "connect error: ${it.firstOrNull()}") })
        s.on("new-message", Emitter.Listener { onMessage(it, isNew = true) })
        s.on("updated-message", Emitter.Listener { onMessage(it, isNew = false) })
        s.connect()
    }

    private fun onMessage(args: Array<out Any?>?, isNew: Boolean) {
        val data = args?.firstOrNull() as? JSONObject ?: return
        val incoming = BlueBubblesApi.messageEvent(data, isNew) ?: return
        SocketBus.incoming.tryEmit(incoming)
        // Notify only for genuinely new incoming messages the user can't see.
        if (isNew && !incoming.message.fromMe && !AppForeground.active) {
            // Prefer an explicit (group) chat name; otherwise resolve the sender's
            // address to a contact name from the persisted index, falling back to
            // the raw address. Read fresh so it reflects the latest address book.
            val title = incoming.chatDisplayName.ifBlank {
                val sender = incoming.message.sender
                sender?.let { contacts().name(it) ?: it } ?: "Message"
            }
            Notifications.post(this, title, incoming.message.text)
        }
    }

    /** The persisted contact index. Reloaded per notification (infrequent — only
     *  background messages) so a name added while the service ran still resolves. */
    private fun contacts(): Contacts = Store.contacts(this)

    override fun onDestroy() {
        socket?.disconnect()
        socket?.off()
        socket = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SocketService"
    }
}
