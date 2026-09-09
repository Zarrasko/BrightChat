package com.gios.lightchat

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import com.gios.lightchat.api.Store
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gios.lightchat.ui.HeadsUpBox
import com.gios.lightchat.ui.theme.LightChatTheme

/**
 * The heads-up box as a real overlay window, used whenever the screen is already on
 * and past the keyguard — see [HeadsUp] for why the screen-off/locked case doesn't get
 * one of these (or any box of ours) at all: it was tried, including the window-flag
 * combination that's supposed to draw over a keyguard, and lost to a launcher-owned
 * `TYPE_KEYGUARD_DIALOG` window no ordinary app can draw above.
 *
 * The point of it being a window rather than an activity is that **nothing else is
 * interrupted**. An activity — floating, translucent, whatever — pauses the activity
 * underneath it, so a message arriving while you were doing something else stopped that
 * something else for four and a half seconds. A `TYPE_APPLICATION_OVERLAY` window
 * doesn't: the app below keeps running, and `FLAG_NOT_FOCUSABLE` (which implies
 * `FLAG_NOT_TOUCH_MODAL`) means every touch outside this box goes straight to it.
 */
object HeadsUpOverlay {

    private val handler = Handler(Looper.getMainLooper())
    private val autoHide = Runnable { hide() }

    private var view: ComposeView? = null
    private var owner: OverlayOwner? = null

    // Read by the composition, so a second message swaps the text in place rather than
    // tearing the window down and building another one.
    private val sender = mutableStateOf("")
    private val body = mutableStateOf("")
    private var chatGuid: String? = null

    /**
     * Puts the box up, or swaps the text of the one already up, and arms the timer.
     *
     * Safe to call from the socket thread: `addView` needs a Looper, so everything here is
     * posted to the main one — which is also why there's no success to report back. If the
     * window manager refuses, the notification has already gone out regardless.
     */
    fun show(context: Context, title: String, text: String, guid: String) {
        val app = context.applicationContext
        handler.post {
            sender.value = title
            body.value = text.trim().take(300)
            chatGuid = guid
            if (view == null) attach(app)
            handler.removeCallbacks(autoHide)
            handler.postDelayed(autoHide, Store.headsUpDurationMs(app))
        }
    }

    /** Takes the box down only if it's showing [guid] — a read on some other chat
     *  shouldn't dismiss the message you're currently being shown. */
    fun hideFor(guid: String) {
        handler.post { if (chatGuid == guid) hide() }
    }

    fun hide() {
        // Posted for the same reason as show: removeView is main-thread-only, and this is
        // also called from MainActivity.onWindowFocusChanged and from the tap handler.
        handler.post {
            handler.removeCallbacks(autoHide)
            val current = view ?: return@post
            val manager = current.context.getSystemService(WindowManager::class.java)
            runCatching { manager?.removeView(current) }
            current.disposeComposition()
            owner?.destroy()
            owner = null
            view = null
        }
    }

    private fun attach(app: Context) {
        val manager = app.getSystemService(WindowManager::class.java) ?: return
        // A ComposeView outside an Activity has none of the owners Compose looks for in
        // the view tree. The lifecycle and saved-state owners are required — it throws on
        // first composition without them; the ViewModelStore one is set for completeness.
        val treeOwner = OverlayOwner().apply { create() }
        val composeView = ComposeView(app).apply {
            // The extensions, not ViewTreeLifecycleOwner.set: that class is a JVM file
            // facade and its `set` is a @JvmName alias that only Java can call.
            setViewTreeLifecycleOwner(treeOwner)
            setViewTreeViewModelStoreOwner(treeOwner)
            setViewTreeSavedStateRegistryOwner(treeOwner)
            setContent {
                LightChatTheme(fillScreen = false) {
                    HeadsUpBox(
                        title = sender.value,
                        text = body.value,
                        onClick = { openThread(app) },
                        onDismiss = { hide() },
                    )
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE is the whole trick: no IME focus taken, no key events, and it
            // implies NOT_TOUCH_MODAL so touches outside our bounds reach the app below.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // Activity.attach injects this; a hand-built LayoutParams doesn't get it,
                // and without it ViewRootImpl draws the whole box on the software path.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }

        val result = runCatching { manager.addView(composeView, params) }
        Log.d(
            "HeadsUpOverlay",
            result.fold(onSuccess = { "addView succeeded" }, onFailure = { "addView failed: $it" }),
        )
        if (result.isFailure) {
            composeView.disposeComposition()
            treeOwner.destroy()
            return
        }
        treeOwner.resume()
        owner = treeOwner
        view = composeView
    }

    private fun openThread(app: Context) {
        val intent = Intent(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        chatGuid?.let { intent.putExtra(Notifications.EXTRA_CHAT_GUID, it) }
        runCatching { app.startActivity(intent) }
        hide()
    }

    /**
     * The three owners a `ComposeView` needs when it isn't inside an Activity. Minimal on
     * purpose: nothing here is ever saved or restored — the box is transient, and if the
     * process dies mid-box there is nothing worth bringing back.
     */
    private class OverlayOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun create() {
            savedState.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun resume() {
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
