package com.gios.lightchat

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.gios.lightchat.api.Store
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Whether this phone will actually let LightChat notice a message while it's idle, and
 * how far it's currently being held back.
 *
 * The thing that makes an always-on messaging app quiet on Android isn't Doze, which
 * [PollAlarm] is already built around — it's **App Standby buckets**, which Doze
 * documentation barely mentions. The longer the phone goes unused, the further the system
 * demotes an app that isn't being opened: active → working set → frequent → rare →
 * restricted. Each step defers its alarms harder, and by `rare` an
 * `setAndAllowWhileIdle` alarm asking for 5 minutes is held for **two hours**;
 * `restricted` holds it for a day. So the exact situation the catch-up poll exists for —
 * the phone sitting untouched overnight, LightChat not opened — is the situation the
 * system throttles it out of. The app looks fine, the alarm is armed, and nothing fires.
 *
 * There is one lever that turns this off rather than negotiating with it: the
 * battery-optimisation allowlist. An allowlisted app is put in the **exempt** bucket, so
 * no bucket deferral applies at all, and it also keeps network access during Doze instead
 * of getting a ~10 second window per alarm. On LightOS there is no Settings screen for it,
 * so it's the usual one-time adb grant (see [ADB_COMMAND]) — [isExempt] is how the app
 * finds out whether it got one, and it's surfaced in Settings because "did that grant
 * stick?" is otherwise unanswerable from the phone.
 */
object Delivery {

    /** The one-time grant. Survives reboots and app updates; there's no UI for it. */
    const val ADB_COMMAND = "adb shell dumpsys deviceidle whitelist +com.gios.lightchat"

    /**
     * On the battery-optimisation allowlist — i.e. in the exempt standby bucket, with no
     * alarm deferral and no Doze network cut. This is the single biggest difference
     * between "notifies within minutes" and "notifies when you pick the phone up".
     */
    fun isExempt(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * The system dialog for the allowlist, or null when this build has no activity for it.
     * LightOS usually doesn't — it ships almost no Settings UI — which is why the adb
     * command is the documented route and this is only tried first.
     *
     * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` needs the matching (normal, granted at
     * install) permission or the dialog rejects the request outright.
     */
    fun exemptionIntent(context: Context): Intent? {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:" + context.packageName))
        val resolves = context.packageManager
            .queryIntentActivities(intent, 0)
            .isNotEmpty()
        return if (resolves) intent else null
    }

    /**
     * The app's own standby bucket. No permission needed — [UsageStatsManager] only
     * refuses to answer for *other* packages. Diagnostic only: if this reads `rare` or
     * `restricted` while [isExempt] is false, the poll is being deferred by hours and
     * that, not the code, is why nothing arrived.
     */
    fun bucketName(context: Context): String {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return "unknown"
        val bucket = runCatching { usage.appStandbyBucket }.getOrDefault(-1)
        return when {
            bucket <= 0 -> "unknown"
            // 5. Not a named constant in the SDK, but it's what the allowlist puts us in and
            // the one value that means nothing here is being deferred.
            bucket < UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "exempt"
            bucket <= UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            bucket <= UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working set"
            bucket <= UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            bucket <= UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            else -> "restricted"
        }
    }

    /**
     * Two lines for the Settings screen: whether background delivery is unthrottled, and
     * when the app last actually heard from the server. The second line is the one worth
     * having — a chain that silently stopped firing hours ago is invisible without it.
     */
    fun healthLines(context: Context): Pair<String, String> {
        val exempt = isExempt(context)
        val first = if (exempt) {
            "Background delivery: unrestricted"
        } else {
            "Background delivery: throttled (" + bucketName(context) + ")"
        }
        val okAt = Store.lastPollOkAt(context)
        val fails = Store.pollFailures(context)
        val second = when {
            okAt == 0L -> "No background check has run yet"
            else -> {
                val stamp = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(okAt))
                val age = ago(System.currentTimeMillis() - okAt)
                if (fails > 0) {
                    "Last checked $stamp ($age) · $fails failed since"
                } else {
                    "Last checked $stamp ($age)"
                }
            }
        }
        return first to second
    }

    private fun ago(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            else -> "${minutes / (60 * 24)}d ago"
        }
    }
}
