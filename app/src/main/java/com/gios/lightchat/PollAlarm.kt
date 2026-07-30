package com.gios.lightchat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.gios.lightchat.api.Store

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
 * possible at all. It's inexact, which also means no `SCHEDULE_EXACT_ALARM` permission —
 * the exact variants need it on 31+.
 *
 * Each firing schedules the next one: there is no repeating form of the allow-while-idle
 * alarms. That makes the chain a single thread with no redundancy — one lost firing and
 * the app goes quiet indefinitely, which is why [looksStalled] exists and why
 * [DeliveryWorker] runs the same catch-up off a completely different system service.
 *
 * **How long the interval actually is** is not up to us. Doze throttles allow-while-idle
 * alarms to roughly one per nine minutes, and App Standby defers them by up to a day once
 * the app has been left alone long enough (see [Delivery]). The interval here is therefore
 * a request, and the honest one to make depends on whether the app is allowlisted.
 */
class PollAlarm : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Armed twice, and the first one is the important one. A broadcast gets about ten
        // seconds of budget; the work below can outlast it, and a process killed for
        // overrunning (or for memory) does not run `finally`. Arming before any of that
        // means the only way to end the chain is for the system to drop the alarm itself.
        runCatching { schedule(app) }
        val pending = goAsync()
        Thread {
            var ok = false
            try {
                ok = CatchUp.run(app)
            } catch (t: Throwable) {
                Log.w(TAG, "catch-up failed: $t")
            } finally {
                // Re-armed now that the outcome is known, so a failure retries sooner than
                // a success waits. Moves the same single alarm — it doesn't add one.
                runCatching { schedule(app) }
                // Releases the wakelock the broadcast holds. Without this the process is
                // killed after ~10s mid-request, and on some builds gets an ANR.
                pending.finish()
            }
            if (!ok) Log.d(TAG, "poll didn't reach the server; ${Store.pollFailures(app)} in a row")
        }.start()
    }

    companion object {
        private const val TAG = "PollAlarm"

        /** Allowlisted: no bucket deferral and no Doze network cut, so a short interval is
         *  a real interval rather than something the system quietly ignores. */
        private const val INTERVAL_EXEMPT_MS = 5 * 60 * 1000L

        /** Not allowlisted. Just above Doze's ~9 minute floor for allow-while-idle alarms:
         *  asking for less only means the system defers us to about here anyway. */
        private const val INTERVAL_THROTTLED_MS = 10 * 60 * 1000L

        /** After this many consecutive failures, stop asking so often. A server that's off,
         *  or a tunnel that's down, otherwise turns the poll into a battery drain that
         *  achieves nothing. Any success resets the count. */
        private const val BACKOFF_AFTER_FAILURES = 3
        private const val INTERVAL_BACKOFF_MS = 30 * 60 * 1000L

        /** Retry gap after a failure that isn't yet a pattern — the radio was probably just
         *  not up yet. Below Doze's floor deliberately: awake, it fires at a minute; asleep,
         *  the system rounds it up to about nine, and either is better than waiting a full
         *  interval to find out the network came back. */
        private const val RETRY_MS = 60 * 1000L

        /** The interval to ask for, given how throttled we are and how it's been going. */
        fun intervalMs(context: Context): Long = when {
            Store.pollFailures(context) >= BACKOFF_AFTER_FAILURES -> INTERVAL_BACKOFF_MS
            Delivery.isExempt(context) -> INTERVAL_EXEMPT_MS
            else -> INTERVAL_THROTTLED_MS
        }

        /**
         * Arms the next poll. Idempotent — the PendingIntent is a singleton, so calling
         * this from the service, from boot, from the worker and from the activity just
         * moves the one alarm.
         */
        fun schedule(context: Context) {
            val app = context.applicationContext
            val manager = app.getSystemService(AlarmManager::class.java) ?: return
            val fails = Store.pollFailures(app)
            val gap = if (fails in 1 until BACKOFF_AFTER_FAILURES) RETRY_MS else intervalMs(app)
            val at = System.currentTimeMillis() + gap
            runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(app)) }
                .onFailure { Log.w(TAG, "couldn't schedule: $it") }
        }

        /**
         * Whether the chain looks dead: no attempt for well over the interval we asked
         * for. Being generous about it (three intervals) matters — the system is *entitled*
         * to defer an inexact alarm, and treating normal deferral as a fault would mean
         * hammering the server every time the phone wakes.
         *
         * True is not proof the alarm is gone; it's the signal that a cheap catch-up is
         * worth doing now, from whichever cause noticed (screen on, network back, worker).
         */
        fun looksStalled(context: Context): Boolean {
            val last = Store.lastPollAt(context)
            if (last == 0L) return true
            val elapsed = System.currentTimeMillis() - last
            // A clock that went backwards (NTP correction, timezone-ish weirdness) would
            // otherwise read as "checked in the future" and suppress catch-up forever.
            if (elapsed < 0) return true
            return elapsed > 3 * intervalMs(context)
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
