package com.gios.lightchat

import android.content.Context
import android.util.Log
import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.api.Store
import com.gios.lightchat.socket.AppForeground

/**
 * Pulls the conversation list and notifies for anything unread that arrived since the
 * last time we alerted. The backstop under the live socket, and the *only* thing that
 * runs while the phone is asleep — see [PollAlarm] for why a timer inside the service
 * isn't enough.
 *
 * Notification only: no box, no buzz per message, no screen wake. By the time this finds
 * something it is minutes old, and lighting the panel for old news is the behaviour we
 * spent a while removing. One buzz if it found anything at all.
 *
 * Shared by the service's watchdog and the alarm, so both can only ever produce one alert
 * per message: the watermark in [Store.lastAlertedAt] is the single arbiter, and it's
 * persisted precisely because the process dying is the case this exists for.
 */
object CatchUp {
    private const val TAG = "CatchUp"

    /** How many conversations to look at. Anything older than the 50th most recent chat
     *  is not a missed notification, it's history. */
    private const val LIMIT = 50

    fun run(context: Context) {
        val app = context.applicationContext
        val client = client(app) ?: return
        val convos = runCatching { client.conversations(limit = LIMIT) }
            .onFailure { Log.d(TAG, "list failed: $it") }
            .getOrNull() ?: return
        val watermark = Store.lastAlertedAt(app)
        val newest = convos.maxOfOrNull { it.lastDate } ?: return

        // First run seeds the watermark without alerting; otherwise every unread chat on
        // the account would arrive at once the first time a build with this in it starts.
        if (watermark == 0L) {
            Store.setLastAlertedAt(app, newest)
            return
        }
        if (newest <= watermark) return

        val missed = convos.filter { it.unread && !it.lastFromMe && it.lastDate > watermark }
        // Foregrounded: the list on screen is being refreshed anyway, and an alert for
        // something the user is looking at is noise. The watermark still moves.
        if (missed.isEmpty() || AppForeground.active) {
            Store.setLastAlertedAt(app, newest)
            return
        }
        Log.d(TAG, "found ${missed.size} missed")
        val contacts = Store.contacts(app)
        for (convo in missed) {
            val title = convo.displayName.ifBlank {
                convo.participants.firstOrNull()?.let { contacts.name(it) ?: it } ?: "Message"
            }
            Notifications.post(app, title, convo.lastText, convo.guid)
        }
        // Advanced *after* posting, not before: if this dies partway the next run retries,
        // and a retry is harmless — notification ids are per chat, so a repost replaces the
        // same row. Losing an alert is the failure that matters.
        Store.setLastAlertedAt(app, newest)
        HeadsUp.buzz(app)
    }

    private fun client(context: Context): BlueBubblesApi? {
        val url = Store.baseUrl(context) ?: return null
        val password = Store.password(context)?.takeIf { it.isNotBlank() } ?: return null
        return BlueBubblesApi(url, password)
    }
}
