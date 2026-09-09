package com.gios.lightchat

import android.app.KeyguardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import com.gios.lightchat.api.Store

/**
 * The alert side of an incoming message: a buzz, and — only while awake and past the
 * keyguard — a box over whatever the phone is showing. The notification
 * [Notifications.post] raises is the *record* — it stays in LightOS's list and drives
 * LightGlance's dot — and is posted by the caller regardless of anything here, so this
 * is purely additive and always degrades to notification-only.
 *
 * **Screen off, or locked: no box of ours, on purpose, not just as a fallback.** This
 * was tried both as a `showWhenLocked` + `turnScreenOn` activity and, later, as an
 * overlay window additionally carrying `FLAG_DISMISS_KEYGUARD` (matching Light SDK's
 * own `LightOverlay`, used by e.g. Agenda's reminders — `FLAG_SHOW_WHEN_LOCKED` alone
 * isn't honored on this OS without it). Both got the panel to turn on. Neither ever got
 * *seen*: this phone's launcher (Luma, or whichever the user has installed) draws its
 * own `TYPE_KEYGUARD_DIALOG` "Unlock Gate" window — confirmed via `dumpsys window
 * windows`, base layer 311000, above even the system status bar (151000) and
 * notification shade (171000). That window type is privileged; an ordinary app's
 * `TYPE_APPLICATION_OVERLAY` (111000) cannot draw above it no matter which flags it
 * carries — our box was rendering the entire time, `HAS_DRAWN` and `isVisible=true`,
 * just permanently covered. `FLAG_DISMISS_KEYGUARD` also unlocks the device outright to
 * show whatever's under it — a real cost (any future PIN/pattern/biometric lock gets
 * bypassed by a text arriving) for a box that would never win the layer fight it was
 * paid for with. Not worth it. If the launcher ever exposes its own notification-preview
 * hook for its Unlock Gate, that's the path back to a locked-screen alert — not fighting
 * its window layer from here.
 *
 * **Awake and unlocked → [HeadsUpOverlay]**, a real overlay window. Nothing else is
 * interrupted: the app underneath keeps running and every touch outside the box still
 * reaches it.
 *
 * The overlay path needs the `SYSTEM_ALERT_WINDOW` appop — the same trick LightGlance
 * relies on. LightOS has no Settings screen for it, so it's adb-only and one-time:
 *
 *     adb shell appops set com.gios.lightchat SYSTEM_ALERT_WINDOW allow
 *
 * Without it, [show] still buzzes and the notification is still posted; only the
 * awake-and-unlocked box is missing.
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
        // The box is optional (Settings); the buzz above and the shade notification the
        // caller posts are not. Checked per message rather than cached: it's one
        // SharedPreferences read on a path that runs a few times an hour at most.
        if (!Store.headsUpBox(context)) {
            Log.d(TAG, "on-screen alerts off; notification only")
            return
        }
        // BrightControl draws this box for every app now, off the notification posted a moment
        // ago. Drawing ours as well is the same message twice, one box on top of the other.
        if (AlertOwner.ownedElsewhere(context)) {
            Log.d(TAG, "BrightControl owns the box; notification only")
            return
        }
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
        // Screen off, or locked: no box of ours — see the class doc for why (a launcher's
        // own Unlock Gate window that no app overlay can draw above, discovered the hard
        // way). The notification, posted separately before this ever runs, is the alert.
        if (!awakeAndUnlocked(context)) return
        if (!Settings.canDrawOverlays(context)) {
            // Expected on a phone that was never plugged into a computer; the
            // notification already went out, so this is not an error.
            Log.d(TAG, "SYSTEM_ALERT_WINDOW not granted; notification only")
            return
        }
        Log.d(TAG, "presenting overlay for $chatGuid")
        HeadsUpOverlay.show(context, title, text, chatGuid)
    }

    /**
     * Screen on *and* past the lock screen. Locked-but-on gets nothing of ours either —
     * see the class doc for why a box would just be drawn and permanently invisible.
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
}
