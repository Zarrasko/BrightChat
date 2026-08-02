package com.gios.lightchat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.gios.lightchat.api.Store
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.gios.lightchat.hw.LightKey
import com.gios.lightchat.hw.LightKeys
import com.gios.lightchat.hw.LocalWheelBus
import com.gios.lightchat.hw.WheelBus
import com.gios.lightchat.socket.AppForeground
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gios.lightchat.report.Reports
import com.gios.lightchat.report.Screenshot
import com.gios.lightchat.report.ShakeDetector
import com.gios.lightchat.report.Trouble
import com.gios.lightchat.ui.ReportChip
import com.gios.lightchat.ui.ReportReason
import com.gios.lightchat.ui.ReportRequest
import com.gios.lightchat.ui.ReportSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.gios.lightchat.ui.ConversationTab
import com.gios.lightchat.ui.DialerScreen
import com.gios.lightchat.ui.ConversationsScreen
import com.gios.lightchat.ui.tabOf
import com.gios.lightchat.ui.NewMessageScreen
import com.gios.lightchat.ui.SettingsScreen
import com.gios.lightchat.ui.SetupScreen
import com.gios.lightchat.ui.ThreadScreen
import com.gios.lightchat.ui.theme.LightChatTheme

/** The recipient extra on an incoming share. AOSP messaging's key, and what Roll sends. */
private const val SHARE_EXTRA_ADDRESS = "address"

/**
 * A chat-room guid on an incoming share — how a sender addresses a *group*.
 *
 * There is no AOSP convention for this, because AOSP's model of a recipient is an address and
 * a group iMessage does not have one: it is a room on the server with its own identity, and the
 * set of people in it is a property of the room rather than the way you reach it. So this is a
 * key private to these two apps, read from this app's own ChatsProvider — which is what makes
 * it safe to treat as opaque and pass straight through to the API. A sender that doesn't know
 * about it keeps working unchanged; it only ever adds a case.
 */
private const val SHARE_EXTRA_CHAT_GUID = "chat_guid"

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Wheel notches on their way to whichever screen is up. */
    private val wheel = WheelBus()

    /**
     * Every hardware key arrives here first — `DecorView` calls the window callback before
     * it walks the view hierarchy — which is what lets a notch beat the compose bar while
     * it holds focus and the keyboard is up.
     *
     * Both halves of the pair are consumed. One notch is a complete DOWN+UP, and letting
     * the UP through means the focused text field takes it as a keypress: the wheel would
     * type into the message you were about to send.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (LightKeys.of(event)) {
            LightKey.WheelUp -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(1)
                return true
            }
            LightKey.WheelDown -> {
                if (event.action == KeyEvent.ACTION_DOWN) wheel.send(-1)
                return true
            }
            else -> Unit
        }
        return super.dispatchKeyEvent(event)
    }

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
        // Shake three times to report a glitch. Ported from Roll unchanged in behaviour, and
        // deliberately: this is diagnostic UI, not product surface, so it should be one learned
        // gesture across every app on this phone rather than four slightly different ones.
        shake = ShakeDetector(this) { onShaken() }.takeIf { it.available }
        setContent {
            LightChatTheme {
                // Anything written while the phone was offline, or by a build with no key in it,
                // goes out now.
                LaunchedEffect(Unit) { Reports.flush(this@MainActivity) }

                val reports = rememberCoroutineScope()
                val report by reportRequest.collectAsState()
                val sheetOpen by reportSheetOpen.collectAsState()

                // A failure the app noticed on its own offers itself rather than waiting to be
                // shaken about.
                val trouble by Trouble.latest.collectAsState()
                LaunchedEffect(trouble) {
                    val failure = trouble ?: return@LaunchedEffect
                    Trouble.clear()
                    if (reportRequest.value != null) return@LaunchedEffect
                    shake?.stop()
                    Screenshot.capture(window) { bitmap ->
                        reportRequest.value = ReportRequest(ReportReason.Failed, bitmap, failure)
                    }
                }

                // Every screen below can reach the wheel.
                CompositionLocalProvider(LocalWheelBus provides wheel) {
                    if (report != null && sheetOpen) {
                        val pending = report!!
                        BackHandler {
                            reportSheetOpen.value = false
                            reportRequest.value = null
                            shake?.start()
                        }
                        ReportSheet(
                            reason = pending.reason,
                            hasScreenshot = pending.shot != null,
                            failure = pending.failure?.what,
                            seedNote = pending.failure?.let { "Could not ${it.what}" }.orEmpty(),
                            onDismiss = {
                                reportSheetOpen.value = false
                                reportRequest.value = null
                                shake?.start()
                            },
                            onSend = { symptom, note, includeScreenshot ->
                                reportSheetOpen.value = false
                                reportRequest.value = null
                                shake?.start()
                                val shot = pending.shot.takeIf { includeScreenshot }
                                reports.launch {
                                    Reports.enqueue(
                                        this@MainActivity,
                                        Reports.compose(
                                            context = this@MainActivity,
                                            symptom = symptom,
                                            note = note,
                                            screenshot = shot?.let { Screenshot.encode(it) },
                                            failure = pending.failure,
                                        ),
                                    )
                                    Reports.flush(this@MainActivity)
                                }
                            },
                        )
                    } else {
                        Box(Modifier.fillMaxSize()) {
                            LightChatApp(viewModel)
                            // Bottom-start, clear of the compose field's send affordance on the
                            // right. Nothing opens by itself: the chip asks, and says nothing if
                            // ignored — silence is "not now", so an unsent crash log is still
                            // there for the next launch to offer again.
                            report?.let { pending ->
                                Box(
                                    Modifier.align(Alignment.BottomStart).padding(16.dp),
                                ) {
                                    ReportChip(
                                        reason = pending.reason,
                                        onOpen = { reportSheetOpen.value = true },
                                        onExpire = {
                                            reportRequest.value = null
                                            shake?.start()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        handlePasswordExtra(intent)
        handleChatGuidExtra(intent)
        handleSharedImages(intent)
    }

    // Hide the status + navigation bars for a full-screen, edge-to-edge look
    // (matches vandamd's LightOS apps, which call the same thing via Expo). The
    // bars slide back transiently on an edge swipe, then re-hide. Re-applied on
    // focus because returning from the keyboard/recents can resurface them.
    /** Null on a phone with no accelerometer, where the gesture simply does not exist. */
    private var shake: ShakeDetector? = null
    private val reportRequest = MutableStateFlow<ReportRequest?>(null)
    private val reportSheetOpen = MutableStateFlow(false)

    /**
     * Three shakes.
     *
     * The screenshot is taken *here*, at the moment of the gesture, rather than when the sheet
     * opens — by then the thing that looked wrong may have redrawn itself, and a report about a
     * glitch whose picture shows the app working is worse than one with no picture.
     *
     * The detector is stopped while a report is pending, so shaking at the chip does not queue a
     * second one behind it; every path that clears the request starts it again.
     */
    private fun onShaken() {
        if (reportRequest.value != null) return
        shake?.stop()
        Screenshot.capture(window) { bitmap ->
            reportRequest.value = ReportRequest(ReportReason.Shaken, bitmap)
        }
    }

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
        shake?.start()
        Notifications.clear(this)
        // The user is here; a box telling them about a message they're about to read is
        // just something in the way.
        HeadsUpOverlay.hide()
        // Re-pull the conversation list on every return to the app, not just cold
        // start — the socket only runs while the service does. Rate-guarded in the
        // ViewModel so this doesn't duplicate the init refresh.
        viewModel.refreshOnResume()
        // Re-arm the asleep-phone poll. Idempotent, and it repairs the chain if a firing
        // was ever lost (force-stop cancels every alarm an app has).
        PollAlarm.schedule(this)
        // And the backstop that notices when that chain has gone missing entirely.
        DeliveryWorker.ensure(this)
        // Anything held from a previous visit is stale now — the user is here and the list
        // shows it, so a notification for it would be a row about a message they can see.
        PendingAlerts.clearAll()
        // Re-lift grayscale if the user left with the image viewer open.
        ColorMode.onAppVisible(this)
    }

    override fun onStop() {
        super.onStop()
        AppForeground.active = false
        // On this phone, leaving the app almost always means the screen went off. Messages
        // that arrived while it was open were deliberately not alerted for, on the
        // assumption the user was looking at them — an assumption that expires right here.
        // Post them now, plainly: no buzz and no box, since they arrived while the phone was
        // in hand and the point is only that they end up in the notification list (and so on
        // LightGlance's dot) rather than nowhere. See PendingAlerts.
        flushPendingAlerts()
        // The rest of the phone must stay B&W even if the viewer is still open.
        ColorMode.onAppHidden(this)
    }

    private fun flushPendingAlerts() {
        val held = PendingAlerts.drain()
        if (held.isEmpty()) return
        held.forEach { Notifications.post(this, it.title, it.text, it.chatGuid) }
        // These have now been alerted for, so the catch-up's watermark may pass them. It is
        // deliberately held below anything suppressed while the app was open (see CatchUp),
        // and without moving it here the next background poll would find the same messages
        // still unread — on a server with no Private API nothing ever marks them read — and
        // post and buzz for them a second time.
        val newest = held.maxOf { it.date }
        Store.setLastAlertedAt(this, maxOf(Store.lastAlertedAt(this), newest))
    }

    // singleTask, so a re-launch with a fresh extra comes through here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePasswordExtra(intent)
        handleChatGuidExtra(intent)
        handleSharedImages(intent)
    }

    /**
     * A photograph shared in from another app — Roll's send picker, or anything else that
     * registers an image share.
     *
     * **The URIs are copied into this app's cache before anything else happens.** A share
     * grant is scoped to the receiving *activity's* lifetime, so holding the URI and reading
     * it later — after the send coroutine has been rescheduled, or after a configuration
     * change — hands back a SecurityException that looks like a corrupt photograph. Copying
     * is also what makes the existing send path usable unchanged: it takes `File`s, because
     * the in-app picker walks the filesystem directly rather than going through MediaStore.
     *
     * The recipient rides in an `address` extra — the AOSP messaging convention, and what
     * Roll sends for a person. A group instead rides in `chat_guid`, since a group has no
     * address to put in the AOSP extra (see [SHARE_EXTRA_CHAT_GUID]). With neither, the
     * photographs wait until a thread is opened.
     */
    private fun handleSharedImages(intent: Intent?) {
        if (intent == null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                @Suppress("DEPRECATION")
                listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))

            Intent.ACTION_SEND_MULTIPLE ->
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

            else -> return
        }
        if (uris.isEmpty()) return
        // Consumed, so an activity recreation doesn't send the same photographs twice.
        intent.removeExtra(Intent.EXTRA_STREAM)
        val address = intent.getStringExtra(SHARE_EXTRA_ADDRESS)?.trim().orEmpty()
        val chatGuid = intent.getStringExtra(SHARE_EXTRA_CHAT_GUID)?.trim().orEmpty()
        intent.removeExtra(SHARE_EXTRA_ADDRESS)
        intent.removeExtra(SHARE_EXTRA_CHAT_GUID)
        val files = uris.mapNotNull { copyIntoCache(it) }
        if (files.isEmpty()) return
        viewModel.receiveShared(address, chatGuid, files)
    }

    /** Copies a shared URI into `cacheDir/shared-in`, returning null if it can't be read. */
    private fun copyIntoCache(uri: Uri): java.io.File? = runCatching {
        val dir = java.io.File(cacheDir, "shared-in").apply { mkdirs() }
        // The name only has to carry a plausible extension — the send reads the mime type
        // off it — and be unique enough that two shares in a row don't collide.
        val extension = contentResolver.getType(uri)
            ?.substringAfterLast('/', "")
            ?.takeIf { it.isNotBlank() && it.length <= 5 }
            ?: "jpg"
        val out = java.io.File(dir, "share-" + System.nanoTime() + "." + extension)
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching null
        out.takeIf { it.length() > 0 }
    }.getOrNull()

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
     * `adb shell am start -n com.gios.lightchat/.MainActivity -e server YOUR_URL -e password YOUR_PASSWORD`
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
fun LightChatApp(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    // Which conversation tab is showing, and where each one is scrolled to. Both
    // live *here*, above the `when` — ConversationsScreen is removed from the
    // composition whenever a thread, settings or the composer is open, so anything
    // remembered inside it is thrown away and coming back would reset the list to
    // the top. rememberSaveable carries them through process death too.
    // **Favorites is the front page.** The starred list is the handful of people this phone is
    // actually for, and opening on the full message list meant scrolling past everyone else to
    // reach them. Known is still one tap away and still called "Messages".
    var tab by rememberSaveable { mutableStateOf(ConversationTab.Favorites) }
    // One scroll position per tab, so switching tabs doesn't scramble the others.
    // Spelled out rather than built in a loop: `remember` inside an iteration is
    // positional, and three named values are easier to trust than that.
    val favoritesScroll = rememberLazyListState()
    val knownScroll = rememberLazyListState()
    val unknownScroll = rememberLazyListState()
    val dialScroll = rememberLazyListState()

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
        tab == ConversationTab.Dial -> DialerScreen(tab = tab, onSelectTab = { tab = it })
        else -> ConversationsScreen(
            viewModel,
            tab = tab,
            listState = when (tab) {
                ConversationTab.Favorites -> favoritesScroll
                ConversationTab.Known -> knownScroll
                ConversationTab.Unknown -> unknownScroll
                // Unreachable — the branch above catches Dial before this runs — but the
                // compiler wants every entry and an exception here would be a crash waiting for
                // whoever adds a fifth tab.
                ConversationTab.Dial -> dialScroll
            },
            onSelectTab = { tab = it },
            onOpenSettings = { showSettings = true },
            onNewMessage = { viewModel.startNewMessage() },
        )
    }
}
