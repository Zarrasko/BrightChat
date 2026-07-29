package com.gios.lightchat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The only thing that can check for messages while the phone is asleep.
 *
 * The socket service holds a coroutine that polls every few minutes, and for a while that
 * looked like enough. It isn't: a `delay()` is a JVM timer, and once the screen is off and
 * the device drops into Doze the CPU suspends and network access is cut for
 * non-exempt apps. The timer doesn't fire, the socket can't reconnect, and nothing
 * notices until the phone is picked up. A foreground service does *not* prevent any of
 * that — it keeps the process alive, not awake.
 *
 * `setAndAllowWhileIdle` is the one alarm that fires during Doze, and firing it grants the
 * app a short window of network access, which is what makes the REST call in [CatchUp]
 * possible at all. It's inexact and the system throttles it to roughly once every nine
 * minutes while idle, so [INTERVAL_MS] is set above that rather than fighting it. Inexact
 * also means no `SCHEDULE_EXACT_ALARM` permission — the exact variants need it on 31+.
 *
 * Each firing schedules the next one: there is no repeating form of the allow-while-idle
 * alarms.
 */
class PollAlarm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Rescheduled first, so a failure below can't end the chain.
        schedule(app)
        val pending = goAsync()
        Thread {
            try {
                CatchUp.run(app)
            } catch (t: Throwable) {
                Log.w(TAG, "catch-up failed: $t")
            } finally {
                // Releases the wakelock the broadcast holds. Without this the process is
                // killed after ~10s mid-request, and on some builds gets an ANR.
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "PollAlarm"

        /** Above the ~9 minute floor the system imposes on allow-while-idle alarms, so we
         *  aren't asking for something it will silently defer anyway. */
        private const val INTERVAL_MS = 15 * 60 * 1000L

        /**
         * Arms the next poll. Idempotent — the PendingIntent is a singleton, so calling
         * this from the service, from boot, and from the activity just moves the one alarm.
         */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val manager = app.getSystemService(AlarmManager::class.java) ?: return
            val at = System.currentTimeMillis() + INTERVAL_MS
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(app)) }
                .onFailure { Log.w(TAG, "couldn't schedule: $it") }
        }

        fun cancel(context: Context) {
            val app = context.applicationContext
            app.getSystemService(AlarmManager::class.java)?.cancel(intent(app))
        }

        private fun intent(app: Context): PendingIntent = PendingIntent.getBroadcast(
            app,
            0,
            Intent(app, PollAlarm::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
