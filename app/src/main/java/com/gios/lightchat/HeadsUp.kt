package com.gios.lightchat

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log

/**
 * The alert side of an incoming message: a buzz, and a box over whatever the phone
 * is showing (see [HeadsUpActivity]). The notification [Notifications.post] raises
 * is the *record* — it stays in LightOS's list and drives LightGlance's dot — so
 * this is purely additive and degrades to notification-only.
 *
 * Two ways of showing it, chosen on whether the phone is already awake and unlocked:
 *
 * - **Awake and unlocked → [HeadsUpOverlay]**, a real overlay window. Nothing else is
 *   interrupted: the app underneath keeps running and every touch outside the box still
 *   reaches it. An activity can't do that — anything on top pauses what's below.
 * - **Screen off, or locked → [HeadsUpActivity]**. An overlay window sits below the
 *   keyguard and can't wake the panel, so for the case that matters most — a text
 *   arriving while the phone is face-down on a desk — only an activity with
 *   `showWhenLocked` + `turnScreenOn` will do, and the interruption is moot because
 *   there was nothing on screen to interrupt.
 *
 * Both paths need the `SYSTEM_ALERT_WINDOW` appop: for the overlay it's the obvious
 * reason, and for the activity it's because on Android 14 that appop is what exempts an
 * app from background-activity-start restrictions — the same trick LightGlance relies
 * on. LightOS has no Settings screen for it, so it's adb-only and one-time:
 *
 *     adb shell appops set com.gios.lightchat SYSTEM_ALERT_WINDOW allow
 *
 * Without it, [show] still buzzes and the notification is still posted; only the box
 * is missing.
 */
object HeadsUp {
    private const val TAG = "HeadsUp"

    /** One buzz per burst. Six messages pasted at once shouldn't feel like six. */
    private const val BUZZ_RATE_LIMIT_MS = 1_500L

    @Volatile private var lastBuzz = 0L

    /**
     * How long to wait for a `chat-read-status-changed` before showing the box. Long
     * enough for the server's chat.db poller to report a read that happened on the Mac at
     * about the same moment, short enough that a genuinely new message still feels
     * immediate.
     */
    private const val READ_GRACE_MS = 2_000L

    private val handler = Handler(Looper.getMainLooper())

    /** chatGuid → the box waiting out its grace period, so [cancel] can find it. */
    private val waiting = HashMap<String, Runnable>()

    /**
     * Buzz now, show the box in a moment.
     *
     * The delay is the whole point. Reading a text on the Mac while the phone sits on the
     * desk used to light the phone up anyway: the `new-message` socket event arrives
     * before the `chat-read-status-changed` that says you've already seen it, so by the
     * time we knew, the panel was on. Waiting [READ_GRACE_MS] lets that second event land
     * and [cancel] the alert. A message that already carries a `dateRead` never alerts at
     * all — that one was read before it even reached us.
     *
     * The buzz is immediate regardless. A late buzz feels like a broken phone, and it
     * isn't what lights the room up at 2am.
     */
    fun show(context: Context, title: String, text: String, chatGuid: String, alreadyRead: Boolean) {
        buzz(context)
        if (alreadyRead) {
            Log.d(TAG, "message already read elsewhere; no box")
            return
        }
        val app = context.applicationContext
        val pending = Runnable { present(app, title, text, chatGuid) }
        handler.post {
            waiting.remove(chatGuid)?.let { handler.removeCallbacks(it) }
            waiting[chatGuid] = pending
            handler.postDelayed(pending, READ_GRACE_MS)
        }
    }

    /** The chat was read somewhere (`chat-read-status-changed`) — drop any box still
     *  waiting on its grace period, and take down one already up for it. */
    fun cancel(chatGuid: String) {
        handler.post {
            waiting.remove(chatGuid)?.let { handler.removeCallbacks(it) }
        }
        HeadsUpOverlay.hideFor(chatGuid)
    }

    private fun present(context: Context, title: String, text: String, chatGuid: String) {
        handler.post { waiting.remove(chatGuid) }
        if (!Settings.canDrawOverlays(context)) {
            // Expected on a phone that was never plugged into a computer; the
            // notification already went out, so this is not an error.
            Log.d(TAG, "SYSTEM_ALERT_WINDOW not granted; notification only")
            return
        }
        // Awake and unlocked: the window, so nothing the user is doing stops.
        if (awakeAndUnlocked(context)) {
            HeadsUpOverlay.show(context, title, text, chatGuid)
            return
        }

        val intent = Intent(context, HeadsUpActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Replace the box that's already up rather than stacking a second one:
            // singleTop + this flag means a burst re-uses one activity via onNewIntent.
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_TEXT, text)
            .putExtra(Notifications.EXTRA_CHAT_GUID, chatGuid)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "background activity start refused: $it") }
    }

    /**
     * Screen on *and* past the lock screen. Locked-but-on still takes the activity path:
     * an overlay window is below the keyguard, so it would be perfectly invisible.
     */
    private fun awakeAndUnlocked(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        return power.isInteractive && keyguard?.isKeyguardLocked != true
    }

    /**
     * A double tick. The message channel has vibration disabled (see
     * [Notifications.ensureChannels]) so this is the only buzz — one place to tune,
     * and it still fires when the box can't be shown.
     */
    fun buzz(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBuzz < BUZZ_RATE_LIMIT_MS) return
        lastBuzz = now
        val vibrator = context.getSystemService(VibratorManager::class.java)
            ?.defaultVibrator ?: return
        if (!vibrator.hasVibrator()) return
        // timings/amplitudes: tick, gap, tick. Short enough to read as one event.
        val effect = VibrationEffect.createWaveform(
            longArrayOf(0, 30, 80, 30),
            intArrayOf(0, 180, 0, 180),
            -1,
        )
        runCatching { vibrator.vibrate(effect) }
    }

    const val EXTRA_TITLE = "headsUpTitle"
    const val EXTRA_TEXT = "headsUpText"
}
