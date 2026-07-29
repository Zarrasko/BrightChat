package com.gios.lightchat

import android.content.Context
import android.content.Intent
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
 * The box is an activity rather than a `TYPE_APPLICATION_OVERLAY` window because an
 * overlay sits below the keyguard and cannot wake the panel, which would mean no
 * alert at all for the case that matters most: a text arriving while the phone is
 * face-down on a desk. Getting an activity up from the background needs the
 * `SYSTEM_ALERT_WINDOW` appop, which on Android 14 is what exempts an app from
 * background-activity-start restrictions — the same trick LightGlance relies on.
 * LightOS has no Settings screen for it, so it's adb-only and one-time:
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

    fun show(context: Context, title: String, text: String, chatGuid: String) {
        buzz(context)
        if (!Settings.canDrawOverlays(context)) {
            // Expected on a phone that was never plugged into a computer; the
            // notification already went out, so this is not an error.
            Log.d(TAG, "SYSTEM_ALERT_WINDOW not granted; notification only")
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
     * A double tick. The message channel has vibration disabled (see
     * [Notifications.ensureChannels]) so this is the only buzz — one place to tune,
     * and it still fires when the box can't be shown.
     */
    private fun buzz(context: Context) {
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
