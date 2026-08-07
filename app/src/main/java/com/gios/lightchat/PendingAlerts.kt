package com.gios.lightchat

/**
 * Messages that arrived while the app was open and were deliberately not alerted for,
 * held until the screen goes off.
 *
 * Suppressing an alert for a message you're looking at is right; suppressing it for a
 * message that merely arrived while the app happened to be open is not, and those are the
 * same condition as far as [com.gios.lightchat.socket.AppForeground] is concerned. The
 * gap: a text lands while you're on the conversation list, you press the power button
 * without opening it, and nothing was ever posted — no row in LightOS's list, no
 * LightGlance dot, no record at all. You find out the next time you open the app.
 *
 * So the alert isn't dropped, it's deferred: held here, cancelled if you open that thread,
 * and posted as a plain notification (no buzz, no box — you were holding the phone, you
 * don't need to be interrupted about it) when the app stops, which on this phone means the
 * screen went off or you left. In-memory on purpose: it's a latency optimisation, and the
 * durable backstop is the watermark, which [CatchUp] deliberately holds below anything it
 * suppressed so a poll re-finds it if the process dies with this map full.
 */
object PendingAlerts {

    data class Alert(
        val chatGuid: String,
        val title: String,
        val text: String,
        val date: Long,
        val seen: Boolean = false,
    )

    private val byGuid = LinkedHashMap<String, Alert>()

    /** One entry per chat, newest wins — same shape as the notifications themselves,
     *  which are per-chat and replace each other. */
    fun add(chatGuid: String, title: String, text: String, date: Long, seen: Boolean = false) =
        synchronized(byGuid) {
            byGuid[chatGuid] = Alert(chatGuid, title, text, date, seen)
            Unit
        }

    /**
     * The thread was opened, so the message has been seen. Flagged rather than removed:
     * the flush must still advance the watermark past it, because on a server with no
     * Private API nothing ever marks it read and the next [CatchUp] would re-find it —
     * and buzz — for a message the user already read. [chatGuids] plural because a
     * forked group spans several rooms.
     */
    fun markSeen(chatGuids: Collection<String>) = synchronized(byGuid) {
        chatGuids.forEach { guid -> byGuid[guid]?.let { byGuid[guid] = it.copy(seen = true) } }
    }

    fun clearAll() = synchronized(byGuid) { byGuid.clear() }

    /** Takes everything held, leaving the map empty. */
    fun drain(): List<Alert> = synchronized(byGuid) {
        val out = byGuid.values.toList()
        byGuid.clear()
        out
    }
}
