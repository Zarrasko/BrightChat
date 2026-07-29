package com.craigeley.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.craigeley.chat.api.Store
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.craigeley.chat.socket.AppForeground
import com.craigeley.chat.ui.ConversationTab
import com.craigeley.chat.ui.ConversationsScreen
import com.craigeley.chat.ui.tabOf
import com.craigeley.chat.ui.NewMessageScreen
import com.craigeley.chat.ui.SettingsScreen
import com.craigeley.chat.ui.SetupScreen
import com.craigeley.chat.ui.ThreadScreen
import com.craigeley.chat.ui.theme.ChatTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        Notifications.ensureChannels(this)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableImmersive()
        setContent {
            ChatTheme {
                ChatApp(viewModel)
            }
        }
        handlePasswordExtra(intent)
        handleChatGuidExtra(intent)
    }

    // Hide the status + navigation bars for a full-screen, edge-to-edge look
    // (matches vandamd's LightOS apps, which call the same thing via Expo). The
    // bars slide back transiently on an edge swipe, then re-hide. Re-applied on
    // focus because returning from the keyboard/recents can resurface them.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersive()
    }

    private fun enableImmersive() {
        // Hide the top status bar only; keep the nav bar so the bottom compose
        // field clears the gesture strip. Transient-on-swipe, re-applied on focus.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Track foreground so the socket service only notifies when the user has
    // actually left the app, and clear any message notification on return.
    override fun onStart() {
        super.onStart()
        AppForeground.active = true
        Notifications.clear(this)
        // Re-lift grayscale if the user left with the image viewer open.
        ColorMode.onAppVisible(this)
    }

    override fun onStop() {
        super.onStop()
        AppForeground.active = false
        // The rest of the phone must stay B&W even if the viewer is still open.
        ColorMode.onAppHidden(this)
    }

    // singleTask, so a re-launch with a fresh extra comes through here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePasswordExtra(intent)
        handleChatGuidExtra(intent)
    }

    /** A tapped message notification carries its chat's guid — jump straight to
     *  that thread rather than wherever the app was left. */
    private fun handleChatGuidExtra(intent: Intent?) {
        val guid = intent?.getStringExtra(Notifications.EXTRA_CHAT_GUID)
            ?.takeIf { it.isNotBlank() } ?: return
        // Consume it so an activity recreation doesn't re-trigger the jump.
        intent.removeExtra(Notifications.EXTRA_CHAT_GUID)
        viewModel.openByGuid(guid)
    }

    /**
     * Lets setup be pushed over adb instead of typed on the phone:
     * `adb shell am start -n com.craigeley.chat/.MainActivity -e server YOUR_URL -e password YOUR_PASSWORD`
     * The `server` extra is optional once a URL is already stored.
     */
    private fun handlePasswordExtra(intent: Intent?) {
        val password = intent?.getStringExtra("password")?.takeIf { it.isNotBlank() } ?: return
        val server = intent.getStringExtra("server")?.takeIf { it.isNotBlank() }
            ?: Store.baseUrl(this) ?: return
        viewModel.saveSetup(server, password)
    }
}

@Composable
fun ChatApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    // Which conversation tab is showing, and where each one is scrolled to. Both
    // live *here*, above the `when` — ConversationsScreen is removed from the
    // composition whenever a thread, settings or the composer is open, so anything
    // remembered inside it is thrown away and coming back would reset the list to
    // the top. rememberSaveable carries them through process death too.
    var tab by rememberSaveable { mutableStateOf(ConversationTab.Known) }
    // One scroll position per tab, so switching tabs doesn't scramble the others.
    // Spelled out rather than built in a loop: `remember` inside an iteration is
    // positional, and three named values are easier to trust than that.
    val favoritesScroll = rememberLazyListState()
    val knownScroll = rememberLazyListState()
    val unknownScroll = rememberLazyListState()

    // A tapped notification can open a thread that isn't on the current tab (a
    // message from an unknown number while Known is showing). Follow it, so closing
    // the thread lands on the list that actually contains it instead of one where
    // the chat you were just reading is nowhere to be seen.
    val openGuid = state.open?.guid
    LaunchedEffect(openGuid) {
        val open = state.open ?: return@LaunchedEffect
        tab = tabOf(open, state.contacts, state.favorites)
    }

    when {
        !state.isConfigured -> {
            // A rejected password sends us back here; make sure settings is dismissed.
            showSettings = false
            SetupScreen(viewModel)
        }
        state.composingNew -> {
            BackHandler { viewModel.cancelNewMessage() }
            NewMessageScreen(viewModel)
        }
        showSettings -> {
            BackHandler { showSettings = false }
            SettingsScreen(viewModel, onBack = { showSettings = false })
        }
        state.open != null -> {
            BackHandler { viewModel.closeThread() }
            ThreadScreen(viewModel)
        }
        else -> ConversationsScreen(
            viewModel,
            tab = tab,
            listState = when (tab) {
                ConversationTab.Favorites -> favoritesScroll
                ConversationTab.Known -> knownScroll
                ConversationTab.Unknown -> unknownScroll
            },
            onSelectTab = { tab = it },
            onOpenSettings = { showSettings = true },
            onNewMessage = { viewModel.startNewMessage() },
        )
    }
}
