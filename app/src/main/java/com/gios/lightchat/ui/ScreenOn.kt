package com.gios.lightchat.ui

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

/**
 * Holds the panel awake for as long as [active] is true, and lets go the moment it isn't.
 *
 * For the handful of things this app does that take longer than the display timeout and have
 * nothing to touch while they run: a newsletter going out one recipient at a time, and a
 * dictation being recorded and sent away to be transcribed. Everything else is fast enough that
 * the screen going dark is the correct behaviour.
 *
 * The view's own `keepScreenOn` rather than a window flag or a wake lock: no permission, no
 * activity lookup from inside a composable, and the platform drops it with the view — so there is
 * no path where this is left holding the screen after the thing that wanted it is gone.
 */
@Composable
fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        if (!active) return@DisposableEffect onDispose { }
        ScreenHold.acquire(view)
        onDispose { ScreenHold.release(view) }
    }
}

/**
 * Counts holders, because [LocalView] is the one root view for the whole composition: two
 * callers that overlap would otherwise have the first one's release turn the screen off under
 * the second. That overlap is real and not rare — stopping a dictation makes it listening=false
 * and transcribing=true in the same frame.
 *
 * Compose effects run on the main thread, so a plain int is enough; there is nothing here to
 * synchronize against.
 */
private object ScreenHold {
    private var holders = 0

    fun acquire(view: View) {
        holders++
        if (holders == 1) view.keepScreenOn = true
    }

    fun release(view: View) {
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) view.keepScreenOn = false
    }
}
