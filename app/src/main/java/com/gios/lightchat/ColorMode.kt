package com.gios.lightchat

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Temporarily lifts LightOS's forced grayscale — vandamd's zero-camera trick.
 * The Light Phone's B&W look is just Android's accessibility color-correction
 * (daltonizer) pinned to grayscale, a secure setting; flipping
 * `accessibility_display_daltonizer_enabled` off shows true color instantly
 * (it's a SurfaceFlinger color-matrix change, no restart).
 *
 * Writing it needs `WRITE_SECURE_SETTINGS`, grantable only over adb (one-time):
 *
 *     adb shell pm grant com.gios.lightchat android.permission.WRITE_SECURE_SETTINGS
 *
 * Without the grant every call here no-ops (the SecurityException is swallowed)
 * and the viewer simply stays grayscale like the rest of the phone.
 *
 * [acquire]/[release] bracket the lifetime of any screen that wants colour — the
 * full-screen image viewer, the photo picker with its camera, and the background
 * editor; the [onAppHidden]/[onAppVisible] pair (MainActivity.onStop/onStart)
 * restores grayscale while the user is elsewhere on the phone and re-lifts it if
 * they come back with the viewer still open.
 *
 * This used to write the setting **on transitions** — lift on the 0→1 holder, restore
 * on the 1→0 — and that shape is exactly what BrightMusic's v0.41 report caught when it
 * inherited this file: a transition that is missed, or that fires while LightOS has
 * already re-pinned the setting underneath us, strands the panel in the wrong mode with
 * no call left that will ever correct it. The lift also early-returned when the
 * daltonizer was already off ("already colour") without recording anything, so the
 * matching restore had nothing to put back. light-reports#35 is the same failure from
 * the other side: LightOS pins grayscale during the launch handoff, the one-shot lift
 * loses the race, and colour never engages until some activity swap re-runs
 * onStart. So this is now the same [apply] model BrightMusic moved to: every entry
 * point states what the panel should be *right now* and makes it so.
 */
object ColorMode {
    private const val TAG = "ColorMode"
    private const val ENABLED = "accessibility_display_daltonizer_enabled"
    private const val MODE = "accessibility_display_daltonizer"

    /** Whether *we* are the reason the panel is in colour. Never inferred from the
     *  setting itself — reading the setting to decide is what made the old lift lose
     *  races against LightOS writing the same key. */
    private var holdingColour = false

    /** The daltonizer mode to put back. Defaults to 0 (LightOS pins simulate
     *  monochromacy), which is also what recovers a phone left stuck in colour by an
     *  older build: restoring 0 is always the right grayscale. */
    private var savedMode: Int = 0

    /**
     * How many screens currently want colour, not whether one does — there are three
     * holders now (the image viewer, the photo picker, the background editor), and with
     * a boolean whichever released first would turn colour off underneath the others.
     * Kept across activity stop/start.
     */
    private var holders = 0

    /** False while the app is in the background, where the rest of the phone must
     *  stay mono. */
    private var appVisible = true

    /** A screen that wants true colour opened: hold it until [release]. */
    fun acquire(context: Context) {
        holders++
        apply(context)
    }

    /** Back to grayscale, immediately. Hiding the flip is the *viewer's* job: it
     *  calls this only once it has faded the photo out to pure black — black is
     *  identical in color and mono, so the flip can't be seen (see
     *  ImageViewerScreen's close sequence). Trying to hide it here with a timer
     *  instead never worked: whichever side of the dismissal frame the flip
     *  landed on, either the full-screen photo or its inline thumbnail was
     *  visibly desaturated. */
    fun release(context: Context) {
        if (holders > 0) holders--
        apply(context)
    }

    /** App left the foreground — the rest of the phone should be B&W even if
     *  the viewer is still open underneath. */
    fun onAppHidden(context: Context) {
        appVisible = false
        apply(context)
    }

    /** App back in the foreground — re-lift if anything still wants colour. Doesn't
     *  touch [holders]: leaving the app isn't the same as closing the viewer. */
    fun onAppVisible(context: Context) {
        appVisible = true
        apply(context)
    }

    /**
     * Put the panel where it should be, from scratch, every time.
     *
     * Idempotent and stateless about how it got here, which is the whole point: a
     * missed or overwritten transition self-corrects on the next call instead of
     * stranding the phone in the wrong mode until the process restarts.
     */
    private fun apply(context: Context) {
        val wantColour = holders > 0 && appVisible
        if (wantColour == holdingColour) return
        val resolver = context.contentResolver
        try {
            if (wantColour) {
                // Remember what to put back *before* turning it off. If it is already
                // off, the stored 0 is right anyway: LightOS pins monochromacy, so 0 is
                // what "on" means here.
                savedMode = Settings.Secure.getInt(resolver, MODE, 0)
                Settings.Secure.putInt(resolver, ENABLED, 0)
            } else {
                Settings.Secure.putInt(resolver, MODE, savedMode)
                Settings.Secure.putInt(resolver, ENABLED, 1)
            }
            holdingColour = wantColour
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted; staying grayscale")
        }
    }
}
