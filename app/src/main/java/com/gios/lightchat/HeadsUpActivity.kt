package com.gios.lightchat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gios.lightchat.api.Store
import com.gios.lightchat.ui.HeadsUpBox
import com.gios.lightchat.ui.theme.LightChatTheme

/**
 * The heads-up box: sender, message, gone in a few seconds.
 *
 * Deliberately a *floating* window sized to its content and pinned to the top, not
 * a full-screen translucent one. A full-screen window would swallow every touch on
 * the phone for as long as the box was up; this one only occupies the strip it
 * draws, so the app underneath stays tappable. That app is paused while the box is
 * up (any activity on top does that, floating or not) but remains visible.
 *
 * `showWhenLocked` + `turnScreenOn` are set both in the manifest and here: the
 * manifest attributes cover a cold start, the calls cover a re-use through
 * [onNewIntent] where the window is already built.
 *
 * There is no enter/exit animation (`windowAnimationStyle` is null in the theme):
 * the box appearing is the event, and animating it away only keeps the panel lit for
 * a few more frames.
 *
 * Screen brightness is left alone. LightGlance overrides it down to a couple of
 * percent because it may hold the panel on for a whole night; this is up for four
 * seconds and needs to be readable at arm's length.
 */
class HeadsUpActivity : ComponentActivity() {

    // Named sender/body, not title/text: `title` would collide with Activity's own
    // getTitle/setTitle and Kotlin treats that as an accidental override.
    private var sender by mutableStateOf("")
    private var body by mutableStateOf("")
    private var chatGuid: String? = null

    // A plain Handler rather than lifecycleScope: nothing else in the app pulls in
    // lifecycle-runtime's coroutine extensions, and one postDelayed doesn't justify
    // being the reason that artifact has to resolve.
    private val handler = Handler(Looper.getMainLooper())
    private val dismiss = Runnable { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.apply {
            // No IME focus: a box appearing must not close the keyboard of whatever
            // the user is typing in underneath.
            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            setGravity(Gravity.TOP)
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
            )
        }
        // Only one box at a time: if the screen went off while a window was up, the
        // activity is now the one showing it.
        HeadsUpOverlay.hide()
        live = this
        read(intent)
        // read() finishes us when the extras are empty; no point composing.
        if (isFinishing) return
        setContent {
            LightChatTheme(fillScreen = false) {
                HeadsUpBox(
                    title = sender,
                    text = body,
                    onClick = { openThread() },
                    onDismiss = { finish() },
                )
            }
        }
    }

    /** A second message while the box is up: swap the content, restart the timer. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        read(intent)
    }

    private fun read(intent: Intent?) {
        sender = intent?.getStringExtra(HeadsUp.EXTRA_TITLE).orEmpty()
        // Long texts are truncated in the layout, but cap here too so a pasted wall
        // of text isn't carried around in an Intent extra.
        body = intent?.getStringExtra(HeadsUp.EXTRA_TEXT).orEmpty().trim().take(300)
        chatGuid = intent?.getStringExtra(Notifications.EXTRA_CHAT_GUID)
        if (sender.isBlank() && body.isBlank()) { finish(); return }
        armDismiss()
    }

    private fun armDismiss() {
        handler.removeCallbacks(dismiss)
        handler.postDelayed(dismiss, Store.headsUpDurationMs(this))
    }

    /** Tapping the box opens that thread, exactly like tapping the notification. */
    private fun openThread() {
        val guid = chatGuid
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (guid != null) open.putExtra(Notifications.EXTRA_CHAT_GUID, guid)
        startActivity(open)
        finish()
    }

    override fun onDestroy() {
        if (live === this) live = null
        handler.removeCallbacks(dismiss)
        super.onDestroy()
    }

    companion object {
        /**
         * The instance currently on screen, if any. Held so [HeadsUpOverlay] can replace
         * it: the activity is only used when the screen was off, and once the user has
         * unlocked, a second message should come as a window that doesn't pause anything
         * rather than stack on top of this. Cleared in [onDestroy], so it isn't a leak.
         */
        @Volatile
        private var live: HeadsUpActivity? = null

        fun dismissLive() {
            val current = live ?: return
            current.runOnUiThread { current.finish() }
        }
    }
}
