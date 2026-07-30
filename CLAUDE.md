# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Chat is a from-scratch **iMessage client** for the **Light Phone III** — a
single-module Android app (Kotlin + Jetpack Compose, `app/`). It talks to a
self-hosted **BlueBubbles Server** (running on an always-on Mac signed into
iMessage) over its REST API, styled after the phone's native messaging app.
Sibling of `reel`, `hive`, `jot`, `pod`, and `ask`. There are no tests.

It exists to replace **OpenBubbles**, whose Flutter app is heavy on battery and
buggy about message ordering. A purpose-built thin client over a single socket
on the tailnet is far lighter, and gets ordering right because it owns the sort.

**Phased build:**
- **Phase 1 (done):** read-only over REST — conversation list + thread view,
  pull-to-refresh, correct ordering, contact-name mapping.
- **Phase 2 (done):** send (`POST /api/v1/message/text`, optimistic + server
  echo) + a Socket.IO foreground service for live `new-message`/`updated-message`
  delivery and notifications. This is the working OpenBubbles replacement.
- **Phase 3 (in progress):** image attachments — receive **and** send **(done)**,
  tapbacks/reactions rendered compactly, read receipts, contact-name resolution
  inside notifications **(done)**, persisting the contact index across launches
  **(done)**. The contact index is now persisted to `Store` (normalized key→name map
  as JSON) so `SocketService` — which runs with no activity/ViewModel alive (e.g.
  started at boot) — can resolve a sender's address to a name for notifications.
  Inbound images render inline in the thread (download → cache → EXIF-orient →
  downsample, dependency-free; see `Attachments`). Outbound images: the thread
  compose bar's "+" opens the system photo picker and sends via multipart
  `POST /message/attachment` (optimistic, echo-reconciled like text). Both work over
  plain REST. **Tapbacks/reactions (done):** incoming reactions render compactly
  (folded onto their target message, not shown as their own row); long-press a
  message to send your own via `POST /message/react`, re-tap the same one to remove.
  *Sending* is gated on the server's Private API being live — the app detects this
  from `server/info` (`private_api && helper_connected`) and only offers the picker
  then; *rendering* incoming reactions works regardless (they arrive as normal
  messages). See "The server" for enabling the Private API (SIP + Library Validation
  off on the Mac). **Marking a thread read** is also wired (when the Private API is
  live): opening a thread — and a live incoming message while it's foreground —
  POSTs `chat/:guid/read`, which clears the unread on the account's other devices.
  **Typing indicators** are wired both ways (Private-API, 1:1 only — the server
  filters group typing): the open thread shows an animated `•••` when the other
  party types, and typing in the compose bar POSTs/DELETEs `chat/:guid/typing`.
  **Non-image attachments** (video/audio/vcard/pdf) render as a tappable
  `Type · filename` row; tapping downloads the file and hands off to an external app
  (`ACTION_VIEW` → share chooser → "can't open" line) via a `FileProvider`. (This is
  the last of the Phase 3 list; only inline *playback* of video/audio and history
  pagination remain unbuilt.)
  **Full-screen image viewer (done):** tapping an inline image opens
  `ImageViewerScreen` (`ui/ImageViewerScreen.kt`) — drawn as an opaque overlay
  *on top of* the thread (a `Box`, **not** `ChatDetailsScreen`'s early-return
  pattern: that unmounted the `LazyColumn`, so dismissing lost the scroll
  position and re-loaded every inline image — the disorienting-return bug).
  Same loader/caches as the inline render so it appears instantly. Pinch to zoom (capped at 4×, matching the 1080px decode cap), drag
  to pan while zoomed, double-tap to toggle zoom at the tapped point; a single
  tap or Back closes. Pure black, no chrome. The image row's own
  `combinedClickable` re-offers the long-press so tapbacks on images still work
  (the column's handler would otherwise be shadowed by the image's), and a tap
  while the tapback picker is open dismisses the picker instead of opening the
  viewer.
  **Color while viewing (done):** the viewer lifts LightOS's forced grayscale for
  exactly its own lifetime — vandamd's zero-camera trick. The phone's B&W look is
  the accessibility daltonizer pinned to mode 0 (simulate monochromacy), a secure
  setting; `ColorMode` (`ColorMode.kt`) flips
  `accessibility_display_daltonizer_enabled` off on `acquire` (viewer enters
  composition, via `DisposableEffect`) and restores the saved mode on `release` —
  a SurfaceFlinger color-matrix change, so both flips are instant. **Hiding the
  restore is the viewer's close sequence**, not a timer (timers were tried and
  failed both ways — whichever side of the dismissal frame the flip landed on,
  either the full-screen photo or its inline thumbnail visibly desaturated):
  tap/Back sets `closing`, the photo fades out to the black background (120ms),
  `release` fires while the screen is pure black — black is identical in color
  and mono, the one moment the flip can't be seen — a ~70ms hold lets the async
  settings write land, then `onClose()` reveals the thread, already B&W.
  `onAppHidden` restores immediately, no fade (another app's colors are showing).
  `MainActivity.onStop/onStart` → `onAppHidden`/`onAppVisible` keep the rest of
  the phone B&W if the app is backgrounded mid-view and re-lift on return.
  Requires a one-time `adb shell pm grant com.gios.lightchat
  android.permission.WRITE_SECURE_SETTINGS` (signature-level; declared with
  `tools:ignore="ProtectedPermissions"`); ungranted, every call no-ops and the
  viewer stays grayscale. Known gap (zero has it too): a process death mid-view
  leaves the phone in color until the app next runs.
  **Links (done):** iMessage attaches a `*.pluginPayloadAttachment` rich-link
  preview blob to every URL it sends; we can't render the preview and the URL is
  already in the text, so these are dropped at parse time (`parseAttachments`) rather
  than shown as junk file rows. URLs in a message body are linkified
  (`ThreadScreen.linkify` → `LinkAnnotation.Url`) so they're tappable (underlined,
  open in the browser).
  **Read receipts — display (done):** messages carry `dateDelivered`/`dateRead`;
  the thread shows a dim "Delivered" / "Read 3:14 PM" line under your newest sent
  message in a 1:1 (not groups — no single read state). Status changes arrive live
  as `updated-message` socket events through the existing guid merge; no Private
  API needed to *display* (only `markRead` sending is gated).
  **Group-event rows (done):** renames/member changes are messages with
  `itemType != 0` (no text — they used to render as blank turns). Known events
  (rename / member added/removed/left / photo change, see `GroupEvent` in
  `Models.kt`) render as a centered dim line ("Liz named the conversation “X”");
  unknown itemTypes (e.g. FaceTime markers) are dropped in `foldReactions`. The
  socket also subscribes to `group-name-change`/`participant-*` events (same
  serialized-message payload as `new-message`, routed through `messageEvent`; never
  notified). In the list they bump recency but the text preview keeps the newest
  real message; a live rename updates the open thread's title and triggers a
  `refresh()`. Note: renaming a *forked* group can legitimately split its merged
  row, since `groupIdentity` keys on name + participants and dead sibling rooms
  keep the old name.
  **Replies (done, Private-API):** long-press → Reply queues the next send as an
  inline reply (`selectedMessageGuid` on `message/text`); a banner above the
  compose bar shows the target until sent/cancelled. Messages with a
  `threadOriginatorGuid` render a dim `↳ “quote”` line above the turn
  (`ChatMessage.shortDescription` describes the original; "an earlier message"
  when it isn't loaded).
  **Group management (done, Private-API):** tapping a *group* thread's title opens
  `ChatDetailsScreen` — rename (`PUT /chat/:guid`, sent to **every** room of a
  forked group so the name+participants merge key holds), member list with
  add/remove (`POST /chat/:guid/participant/add|remove`; iMessage may fork a new
  room on add), and leave (`POST /chat/:guid/leave`, all rooms). Destructive taps
  confirm by second tap. Read-only member list without the Private API.
  **Failed sends (done):** `ChatMessage.error` is parsed; any sent message with a
  non-zero error shows a full-white "Not delivered" line (replacing its receipt).
  The socket subscribes to `message-send-error` (routed like `updated-message`)
  so the flag lands live — previously a send the Mac accepted but couldn't
  deliver (e.g. a non-iMessage number) failed silently. Complemented by a
  **new-message availability check** (`GET /handle/availability/imessage`,
  Private-API): adding a recipient verifies they can receive iMessages; a flagged
  recipient dims, a line explains, and the compose bar hides until they're removed.
  **List timestamps** are iMessage-style absolute (`ConversationsScreen.listTime`):
  time today, "Yesterday", weekday within a week, then a short date — not
  "18 hours ago".
  **Unread markers (done):** a row with unread messages shows a text-only `• `
  before its title plus a full-white preview line. Derived in
  `BlueBubblesApi.conversations()` from the newest non-group-event message per
  room: `!fromMe && dateRead == 0` (chat.db stamps `dateRead` on incoming messages
  when the chat is read on *any* device, so this is account-wide; group events are
  skipped — they never get a stamp and would pin the dot). Live: `applyIncoming`
  flags a new incoming message/tapback unless that thread is open + foregrounded.
  Cleared by opening the thread here, or by the **`chat-read-status-changed`**
  socket event (the server's chat.db poller — no Private API needed) when the chat
  is read on the Mac/iPhone, which also dismisses the chat's notification. The
  ViewModel's session-lived `clearedUnread` (primary guid → lastDate at clear)
  stops a refresh from resurrecting a just-cleared dot while the server's
  `dateRead` stamp catches up with our `markRead`.
  **Favorites / Known / Unknown tabs (done):** the list is three lists behind a
  bottom icon bar (`ui/Navbar.kt`, the LightFog pattern — Material glyphs at 48dp,
  active white / inactive `#6E6E6E`, no labels, hand-parsed paths rather than a
  material-icons dependency, like `ui/Tapbacks.kt`). `tabOf(convo, contacts,
  favorites)` decides membership in one place, so the three are exhaustive and
  disjoint by construction: starred → Favorites, else `Contacts.knows` (an explicit
  group name, or any participant in the address book) → Known, else Unknown. A tab
  with unread carries a dot on its glyph. Favorites are local (`Store.favorites`,
  newline-joined guids) because BlueBubbles has no favorites concept; long-press a
  row to toggle, and the row moving tabs is the only confirmation.
  **List scroll position (done):** the tab and one `LazyListState` per tab live in
  `LightChatApp`, above the `when` that swaps screens. `ConversationsScreen` leaves
  the composition entirely whenever a thread, settings or the composer is open, so
  anything remembered inside it is discarded — which is why exiting a thread used
  to land back at the top of the list. `rememberSaveable` on the tab carries it
  through process death; `LazyListState` is saveable already. A `LaunchedEffect` on
  the open chat's guid follows the tab to whatever list contains it, so a
  notification tap on an unknown number doesn't close back onto a list the chat
  isn't in.
  **Heads-up box (done):** `HeadsUp.show` buzzes (a double tick — the message
  channel has `enableVibration(false)` so there is exactly one buzz per message,
  whether or not the box appears) and starts `HeadsUpActivity`: sender, two lines,
  4.5s, tap to open the thread, swipe up to dismiss. It is an *activity*, not a
  `TYPE_APPLICATION_OVERLAY` window, because an overlay sits below the keyguard and
  cannot wake the panel — `showWhenLocked` + `turnScreenOn` can. Starting it from
  the background needs the `SYSTEM_ALERT_WINDOW` appop, which on Android 14 is what
  exempts an app from background-activity-start restrictions (LightGlance leans on
  the same thing); `adb shell appops set com.gios.lightchat SYSTEM_ALERT_WINDOW
  allow`, adb-only because LightOS has no Settings screen for it. Ungranted, the
  buzz and the notification still happen. The window is floating and sized to its
  content so touches outside it still reach the app underneath — that app is paused
  while the box is up (anything on top does that) but stays visible. Known gap:
  brightness is left at whatever the system had, so a 3am text lights up at full
  brightness; LightGlance's 2% override wasn't reused because the box has to be
  readable at arm's length.
  **Photo picker (done):** replaces `PickVisualMedia` everywhere. The system picker is
  backed by MediaStore and nothing on LightOS keeps MediaStore current, so photos taken
  minutes earlier weren't offered at all. `Gallery` walks DCIM + Pictures itself
  (`maxDepth(3)`, hidden files and dirs skipped, newest first, capped at 600) — the
  directory listing is the source of truth and can't be stale. Needs the **full**
  `READ_MEDIA_IMAGES` grant: Android 14's "Select photos and videos" grants
  `READ_MEDIA_VISUAL_USER_SELECTED` and leaves `READ_MEDIA_IMAGES` *denied*, which is why
  the denial state names it and links to app settings. Thumbnails go through
  `Attachments.decode(file, 256)` so the EXIF orientation handling isn't duplicated; the
  cache is byte-sized (12MB) rather than entry-counted. 3-up grid, tap to select, each
  cell showing its pick position, Send fires them as separate attachments in order on one
  IO coroutine (sequential — iMessage has no batch, and racing them lands them out of
  order). `ChatViewModel.sendImageFiles`; the old `sendImage(Uri)` path is gone.
  **Inline camera (done):** `CameraScreen`, CameraX `LifecycleCameraController` +
  `PreviewView`, capture grabbing `PreviewView.bitmap` rather than an `ImageCapture`
  round-trip (LightTip's trick — instant on this hardware; the cost is that the photo is
  the viewfinder's size and crop, not the sensor's). Three things that bit us and are
  load-bearing: the shutter is gated on `previewStreamState == STREAMING`, because
  PreviewView returns a bitmap as soon as its *surface* is valid and an all-zero bitmap
  compresses to a perfectly valid black JPEG that would then be sent to somebody;
  `ImplementationMode.COMPATIBLE`, because the default PERFORMANCE mode punches a
  transparent hole in the window (fragile inside the opaque Surface the picker draws in)
  and is also the mode whose `getBitmap()` blocks on a PixelCopy; and every way a CameraX
  bind can fail is asynchronous and swallowed, so failure is detected as "no frames within
  4s" rather than by catching `bindToLifecycle`. Captures go to `cacheDir/camera` to be
  sent and are copied into `DCIM/LightChat` via a MediaStore insert so they're kept —
  direct writes to shared storage aren't permitted on 29+, only reads by path are.
  **Colour while picking (done):** the picker holds `ColorMode` for its whole lifetime,
  camera included (the `DisposableEffect` sits above the early return). `ColorMode` counts
  holders rather than a boolean now that the viewer and the picker both take it — and the
  viewer's release is idempotent (`AtomicBoolean`) because it releases mid-close *and* on
  dispose. Known gap: no fade on the picker's exit, so the thread's inline thumbnails stay
  colour for the ~70ms the settings write takes and then desaturate.
  **Heads-up presentation (done):** two ways of showing the box, chosen on
  `PowerManager.isInteractive && !KeyguardManager.isKeyguardLocked`. Awake and unlocked →
  `HeadsUpOverlay`, a real `TYPE_APPLICATION_OVERLAY` window, because **nothing else gets
  interrupted**: an activity — floating, translucent, whatever — pauses the activity
  underneath it, so a text used to stop whatever you were doing for 4.5s.
  `FLAG_NOT_FOCUSABLE` implies `FLAG_NOT_TOUCH_MODAL` (still true on 34), so touches
  outside the box reach the app below, and no IME focus is taken. Screen off or locked →
  `HeadsUpActivity` still, since a window sits below the keyguard and can't wake the
  panel; `HeadsUpActivity.dismissLive()` lets the overlay replace a box that's still up
  from before the user unlocked. A `ComposeView` outside an Activity needs the ViewTree
  lifecycle and saved-state owners set or it throws on first composition — and note
  `ViewTreeLifecycleOwner.set` is a Java-only `@JvmName` alias, the Kotlin call is the
  `setViewTreeLifecycleOwner` extension. `FLAG_HARDWARE_ACCELERATED` has to be set by hand
  too; `Activity.attach` injects it and a hand-built `LayoutParams` doesn't get it.
  **Don't alert for something already read (done):** reading a text on the Mac lit the
  phone anyway — `new-message` arrives before the `chat-read-status-changed` that says
  you've seen it, so by the time we knew, the panel was on. The box now waits
  `READ_GRACE_MS` (2s) and `chat-read-status-changed` cancels it; a message that already
  carries a `dateRead` never shows one. The buzz stays immediate — a late buzz feels like
  a broken phone, and it isn't what lights the room up at 2am.
  **"null" chats (done):** `JSONObject.optString(k, "")` does *not* return the fallback
  for an explicit JSON null — org.json stores `JSONObject.NULL`, whose `toString()` is
  `"null"`, and that string comes back. BlueBubbles sends `"displayName": null` for an
  unnamed chat, so the list had rows literally titled "null" that counted as *Known*
  because the name was non-blank. `JSONObject.string(key)` in `BlueBubblesApi` is the fix;
  `Contacts.from`/`name` and `Conversation.title` guard the literal too, for an index
  persisted by an older build.
  **Mark all as read (done):** Settings action. Returns its outcome as a string rather
  than setting `state.message`, which the list only renders when *empty* — it would have
  been invisible there and then turned up floating in the next thread opened. Clears by a
  captured guid set, not by `it.unread`, because `MutableStateFlow.update` re-runs on CAS
  contention and a message arriving in that window would lose its dot without a
  `clearedUnread` entry or a receipt. Without the Private API it says so.
  **Tapback gestures (done):** long-press *or* double-tap opens the picker, on message
  bodies and on images (children win hit-testing, so images need their own). Cost: every
  single tap in a thread now waits out the double-tap timeout, which for images means
  opening a photo is ~300ms slower — the trade every gallery with double-tap-to-zoom makes.
  **Scroll on send (done):** your own newest message always scrolls the thread to the
  bottom, wherever you were; someone else's only nudges you if you were already there.
  **Background delivery — six layers (done):** the live socket is the fast path, and it
  is not sufficient. (1) `SocketService`'s watchdog re-pulls every 5min and reconnects a
  socket reporting itself down — but it's a `delay`, a JVM timer, so it only ticks while
  the phone is *awake*: Doze suspends the CPU and cuts the app's network, and a foreground
  service keeps the process alive, not awake. (2) `PollAlarm` +
  `AlarmManager.setAndAllowWhileIdle` is the only thing that runs asleep — it's the one
  alarm that fires in Doze, and firing it grants a short network window, which is what
  makes the REST call possible at all. Inexact, so no `SCHEDULE_EXACT_ALARM`; no repeating
  form exists, so each firing schedules the next, and it's re-armed from the service, from
  `BOOT_COMPLETED` (alarms don't survive reboot), from `MY_PACKAGE_REPLACED`
  (`PackageReplacedReceiver` — an Obtainium update landing while the app isn't running
  otherwise leaves no alarm and no socket until it's opened by hand) and from
  `MainActivity.onStart` (a force-stop cancels every alarm an app has). `goAsync()` + a
  thread, because the broadcast's wakelock is released on return from `onReceive` and the
  request needs longer. (3) `MainActivity.onStart` re-pulls, covering the process being
  killed outright. (4) `DeliveryWorker`, a 15min WorkManager periodic, exists **because it
  fails differently**: periodic work lives in JobScheduler, in the system's own store, so
  it survives process death and is restored after a reboot with no receiver of ours
  involved — it's useless as *the* delivery path (15min minimum, deferred to a maintenance
  window) and good as the thing that notices the alarm chain is gone and re-arms it. It
  also restarts the socket service and runs a catch-up, and returns `Result.retry()` rather
  than `failure()` on error, since a failed periodic work item is dropped. (5) A runtime
  `ACTION_SCREEN_ON`/`ACTION_USER_PRESENT` receiver in the service catches up on pickup,
  gated on `PollAlarm.looksStalled` so normal use doesn't re-pull on every unlock. (6) A
  `registerDefaultNetworkCallback` catches up when the Tailscale tunnel comes back — the
  socket has no idea it missed anything, so reconnecting alone isn't enough.
  `CatchUp` is shared by all of them; `Store.lastAlertedAt` is the single watermark so a
  message alerts exactly once however many layers see it, seeded without alerting on first
  run. Notification only — no box, no screen wake — since anything it finds is minutes old.
  **What actually throttles this is App Standby, not Doze** (`Delivery.kt`): an app the
  user hasn't opened is demoted active → working set → frequent → rare → restricted, and by
  `rare` an allow-while-idle alarm asking for 5min is deferred **two hours** (`restricted`,
  a day) — i.e. the phone-off-overnight case the poll exists for is the case Android
  throttles it out of. `dumpsys deviceidle whitelist +com.gios.lightchat` puts the app in
  the *exempt* bucket, which removes the deferral entirely and keeps network during Doze;
  `Delivery.isExempt` reads it back, `PollAlarm.intervalMs` polls 5min when exempt / 10min
  when not (just above Doze's ~9min floor — asking for less only gets deferred to about
  there anyway) / 30min after 3 consecutive failures, with a 1min retry after the first
  failure. Settings shows the bucket and `Store.lastPollOkAt`, because "the app looks
  connected but hasn't heard from the server in six hours" has no other symptom.
  **Two ways the watermark used to lose messages, both worst after the phone is off a
  while:** (a) a sweep page is a fixed number of *messages*, not chats, so a backlog with
  one busy group in it filled the page and the watermark then jumped over every chat hidden
  behind it — `BlueBubblesApi.sweep(pageSize, coverBackTo, maxPages)` now pages back until
  the sweep actually reaches the watermark (10 pages / 500 messages, then it gives up and
  says so via `Sweep.complete`); (b) when the app was foregrounded the alert was skipped
  *and* the watermark advanced, so a message that arrived while you were in the app and
  never read was gone — `CatchUp` now holds the watermark just below the oldest suppressed
  message, so a later poll re-finds it, and the hold clears itself once the message is read
  (it stops matching `unread`). **The catch-up is two-phase** for the same reason it exists:
  a Doze alarm grants ~10s of network, and a full sweep (50 messages, participants,
  attachment metadata) over a tunnel whose radio just woke up can spend all of it on the
  handshake, getting cut mid-response — a failure indistinguishable from "nothing new". So
  `newestMessageDate()` asks the cheap question first (3 messages, `with:[]`, 4s timeouts)
  and the sweep is only paid for when something actually arrived.
  **Messages arriving while the app is open** are no longer dropped: `AppForeground.active`
  can mean "reading that thread" or "on the list about to lock the phone", and the second
  used to leave no record anywhere. They're held in `PendingAlerts` (in-memory; the
  watermark is the durable backstop), cleared when the thread is opened, and posted as
  plain notifications — no buzz, no box — from `MainActivity.onStop`, which on this phone
  means the screen went off.
  **Unknown senders are silent by default (done):** an iMessage account that has existed for
  years receives a steady trickle from short codes, delivery notices, two-factor senders and
  whoever last had the number, and on this phone every one of them buzzed, woke the panel and put
  a box in front of whatever was on screen. `Store.notifyUnknown` (default **false**, Settings →
  "Unknown senders") gates the *alert* only — a filtered message still merges into the thread,
  still bumps the list and still carries its unread mark. "Known" is `Contacts.knows`: a named
  group, or any participant in the address book — deliberately the same definition as the list's
  Known tab, so the setting reads as "alert me about Known and Favourites" rather than introducing
  a second notion of a stranger. `SenderFilter` exists so the socket path and `CatchUp` cannot
  disagree: a filter that differs between them lets a stranger through whenever the phone happened
  to be asleep. Note `CatchUp` advances the watermark past a filtered message — suppressing it is
  a decision, not a deferral, and holding the line would re-examine it on every poll forever.
  **Receiving a shared photo (done):** `MainActivity` registers `ACTION_SEND` /
  `ACTION_SEND_MULTIPLE` for `image/*`, which is what makes LightChat a share target at all —
  without the filter an explicit intent aimed at this package resolves to nothing, so Roll's
  send button reported "LightChat can't receive photos" on a phone with LightChat installed.
  The two actions are separate and carry differently typed `EXTRA_STREAM` (a `Uri` vs an
  `ArrayList<Uri>`), so both are declared. **The URIs are copied into `cacheDir/shared-in`
  before anything else happens**: a share grant is scoped to the receiving *activity's*
  lifetime, so reading one later — after the send coroutine is rescheduled, or after a
  configuration change — throws a SecurityException that presents as a corrupt image. Copying
  also lets the existing send path work unchanged, since it takes `File`s (the in-app picker
  walks the filesystem, not MediaStore). The recipient arrives in an `address` extra (AOSP
  messaging's convention, and what Roll sends) and `ChatViewModel.receiveShared` sends
  straight to `iMessage;-;<handle>` — constructed, not looked up, like `sendNewImage`, so it
  addresses an existing thread or creates one without needing to know which. With no address
  the photos are held in `pendingShared` and flushed by the next `open()`.
  **Notification deep-links (done):** message notifications are per-chat (id
  hashed from the chat guid, so each thread keeps its own and a newer message
  replaces it) and tapping one opens that thread: the PendingIntent carries
  `Notifications.EXTRA_CHAT_GUID`, `MainActivity` (singleTask — onCreate or
  onNewIntent) hands it to `ChatViewModel.openByGuid`, which matches by guid
  *membership* (forked groups) and — on a cold start, before the list exists —
  queues the open via `pendingOpenGuid` until `loadConversations` lands.
  `Notifications.clear` (app foregrounded) enumerates `activeNotifications`
  rather than tracking ids, so it survives process restarts; the
  foreground-service notification is skipped.
  **Forked group chats (done):** iMessage can split one group into sibling chat
  rooms — same name, identical participants, different guid — with messages divided
  across them by "era". BlueBubbles reports each room as its own chat, so the list
  (keyed on chat guid) showed the group twice, and a room whose newest activity was a
  tapback surfaced as its own "Loved …" thread. We collapse them: see
  `BlueBubblesApi.conversations` + `groupIdentity` and `Conversation.guids` below.

Note: messaging yourself (note-to-self) legitimately shows each message twice —
iMessage stores a sent *and* a received row (two GUIDs). Normal chats don't; the
socket echo of your own sends dedupes by GUID.

## Commands

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21                 # JDK 21 required
./gradlew assembleDebug                                       # build debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk      # install on device
./gradlew assembleRelease                                     # minified, debug-signed so it sideloads
```

Only `arm64-v8a` is built (the Light Phone's ABI). minSdk 34 (matches the device —
Android 14), target/compile SDK 35. JDK 21 runs Gradle; the app compiles to Java
11 bytecode. Android SDK at `/opt/homebrew/share/android-commandlinetools`. The
device shows in `adb devices` as `LightPhoneIII` / model `TLP301` (USB or
adb-over-wifi; it is otherwise reached only over Tailscale).

## Setup / auth

On first launch the app asks for the **BlueBubbles Server URL and password**
(`SetupScreen` → `ChatViewModel.saveSetup`), validates them against
`GET /api/v1/server/info`, and stores both on the device (the password encrypted).
The URL is also editable later in `SettingsScreen` (`ChatViewModel.updateServerUrl`,
which re-validates and bounces the socket). If the API returns 401/403 the app
clears the password and returns to setup.

## Architecture

- **`Store`** (`api/Store.kt`) — SharedPreferences. The **server URL**
  (`baseUrl`/`setBaseUrl`) is entered at setup and editable in Settings — one
  self-hosted server per install; `setBaseUrl` normalizes it (defaults the scheme
  to `https://`, strips a trailing slash). The password is encrypted at rest via `SecureStore`. Also
  persists the **contact index** (`setContacts`/`contacts`) as the normalized
  key→name map (JSON) so `SocketService` can name notification senders without the
  app running; the ViewModel writes it on each contacts load, `signOut` wipes it
  with everything else. Also caches the **Private API flag** (`privateApi`/
  `setPrivateApi`) read from `server/info`, so the UI knows on launch whether to
  offer tapbacks before the first refresh lands.
- **`SenderFilter`** (`SenderFilter.kt`) — the one place that decides whether a message may
  interrupt. Two callers (`SocketService.onMessage` for live messages, `CatchUp` for the poll)
  holding different shapes of the same fact, so it takes a `known` boolean rather than deriving it;
  `knownSender` is the socket's version, off a single incoming message.
- **`SecureStore`** (`api/SecureStore.kt`) — at-rest encryption only. An
  AES-256-GCM key lives non-exportable in the AndroidKeyStore (hardware-backed)
  and encrypts the password. (Trimmed down from `ask`'s version — no Ed25519 /
  Bouncy Castle here; BlueBubbles auth is just the password.)
- **`BlueBubblesApi`** (`api/BlueBubblesApi.kt`) — the REST surface, plain
  `HttpURLConnection` + `org.json` (no networking dependency, like hive/pod).
  Auth is the server password as the `password` query param on every call.
  `serverInfo()` → `GET /server/info` (`ServerInfo.reachable` is the setup check)
  and also reads `private_api && helper_connected` into the `ServerInfo` so the app can
  gate tapback *sending* on the Private API being live; `react(guid,selectedMsgGuid,
  reaction,partIndex)` → `POST /message/react` (Private-API only; `reaction` is a
  `ReactionType.apiValue`, prefix `-` to remove; returns the created reaction message
  to reconcile its echo); `markRead(guid)` → `POST /chat/:guid/read` (Private-API
  only; clears unread across the account's devices); `startTyping(guid)`/
  `stopTyping(guid)` → `POST`/`DELETE /chat/:guid/typing` (Private-API only);
  `messages(guid)` → `GET /chat/:guid/message`
  (`with=handle,attachment`, `sort=DESC`, guid URL-encoded); `send(guid,text,tempGuid,
  method)` → `POST /message/text` (only `chatGuid`+`message` required; we pass a
  `tempGuid` to correlate the echo and a `method` — `private-api` when the server's
  Private API is live, else `apple-script`. The ViewModel picks the method via
  `sendMethod()` off the cached `privateApi` flag, and the *room* to send to via
  `sendTargets()` (see **Forked-group sends** below); parse the created message from the
  response);
  `sendAttachment(guid,bytes,name,mime,tempGuid,method)` → `POST /message/attachment`
  (the one **multipart/form-data** call — built by hand, not via `request()` — same
  `tempGuid`/`method` echo handling as `send`, same `sendTargets()` room selection);
  `downloadAttachment(guid,dest)`
  → `GET /attachment/:guid/download` (streams the raw bytes to a file, for the inline
  image loader); `newChat(address,text)` →
  `POST /chat/new` (starts a 1:1 by sending the first message — macOS Big Sur+
  requires the message inline; returns the new chat guid); `contacts()` → `GET /contact`
  flattened to (address,name) pairs. Message parsing lives in the **companion**
  (`parseMessage`, `messageText`, `messageEvent`) so the socket service decodes
  `new-message`/`updated-message` payloads with the same logic. `parseAttachments`
  drops `*.pluginPayloadAttachment` rich-link blobs (iMessage's URL previews — not
  real files). `messageText` guards `isNull("text")` because org.json's `optString`
  returns the literal string `"null"` for an explicit JSON null (a text-less message
  would otherwise render the word "null"). `ApiException.isAuthError`
  flags 401/403. Timestamps are epoch millis, carried through unchanged.
  - **`conversations()` deliberately does NOT use `chat/query`.** That endpoint
    sorts by an unreliable `lastmessage` cache and often returns an empty
    `lastMessage`, so on this account (~2700 chats) a freshly-active chat can fall
    outside its first page and never appear, and the list shows stale ordering
    (this was the "stuck at 4 hours ago" bug). Instead we drive the list from one
    `POST /message/query` DESC sweep (`with:[chats,chats.participants,attachment]`,
    the newest ~1000 messages) and take the first message seen per chat — already in
    true recency order, with each chat's metadata read off the embedded chat object.
    (`attachment` is in the `with` so an attachment-only last message gets a real
    preview via `ChatMessage.previewText` — `[Photo]` etc. — rather than a blank line;
    without it the sweep can't tell text-less messages from empty ones, which was the
    inconsistent-preview bug.)
    Trade-off: only chats active within the sweep window appear (the recent ones —
    what a messages list shows). **1:1 chats (style 45) return empty
    `participants`; the `chatIdentifier` is the other party's address, so we fall
    back to it** — otherwise 1:1 rows render as "Unknown".
    For the preview the sweep prefers the newest **non-reaction** message (so a row
    reads "So you'll watch…" not a bare "Loved …"), while still bumping recency by
    the newest message's date — a tapback bumps the thread like iMessage does.
  - **Forked-group merge (`conversations` pass 2 + `groupIdentity`).** A group iMessage
    has split into sibling rooms (same name + identical participants, different guid)
    is collapsed into one `Conversation` whose `guids` lists every room (newest-active
    first, so `guid` is the send target). Keyed by `groupIdentity` =
    `displayName + sorted participants` — **groups only** (style 43); 1:1s and unforked
    groups key by their own guid and never merge, so single-room chats are unchanged.
    The thread (`ChatViewModel.open`) fetches each member room independently and
    interleaves them (`foldReactions` sorts by date and folds tapbacks across rooms),
    resilient to one dead room; socket routing, `bumpConversation`, and mark-read all
    match by **guid membership** (`guid in conversation.guids`), not equality.
- **`ChatViewModel`** — single source of truth (`UiState` as a `StateFlow`).
  Drives `Idle → Loading → Ready/Error`. Owns password setup, the conversation
  list, the open thread, sending, and the live feed. **Ordering is enforced
  here** — conversations `sortedByDescending { lastDate }`, messages
  `sortedBy { date }` — which is the whole reason this exists. `sendMessage()`
  appends an optimistic message under a temp guid, then swaps in the server's
  echo (real guid) so the socket's `new-message` dedupes by guid.
  **Forked-group sends (`sendTargets` + `sendAcrossRooms`):** a forked group spans
  several room guids, and not all of them are sendable. With the **Private API** the
  server resolves a chat by DB identity, so any room works — `sendTargets` returns just
  `[convo.guid]`. With **AppleScript** (no Private API), the server's `chat id "…"`
  lookup *fails on a dead/stale fork room* — it throws `-1728` ("Can't get chat id"),
  which drops the server into its DM-only fallback script that rejects groups ("Can't
  use the send message (fallback) script to text a group chat!"). The *live* sibling
  room resolves and delivers fine. So for AppleScript, `sendTargets` returns **all**
  the group's room guids (UUID-form first, then `chat<number>` forms) and
  `sendAcrossRooms` tries each until one delivers. This is safe against double-sending:
  `-1728` fails during resolution, before any message goes out. (Empirically confirmed:
  one forked group's `chat388…` room `-1728`s while its `chat495…` sibling sends — see
  the verified-from-server-logs investigation.) Both `sendMessage` and `sendImage` go
  through this. It collects
  `SocketBus` and folds incoming/updated messages into the list (`bumpConversation`)
  and the open thread; a message for an unknown chat triggers a `refresh()`.
  **Tapbacks:** the open thread keeps a raw message list (`openRaw`, reaction
  messages included) as its source of truth; `state.messages` is always
  `foldReactions(openRaw)`, which attaches each tapback to its target message as a
  `Reaction` (keyed by (target, reactor), latest add wins, a `-`-prefixed removal
  clears it) and drops the reaction rows. Every open-thread mutation goes through
  `updateOpenThread(guid){…}` — guarded so a late send/echo can't clobber a thread
  you've navigated away from — so sends, the socket merge (`mergeRaw`), and
  `sendReaction` all re-fold. `sendReaction(target,type)` is optimistic + echo-
  reconciled like `sendMessage`, toggles off if you already hold that reaction, and
  no-ops unless `state.privateApi` (the cached `server/info` capability).
  `markReadIfPrivate(guid)` fires `markRead` best-effort (off-main, errors ignored)
  when you open a thread and on a foreground incoming message, so reading here
  clears the unread on your other devices — also gated on `state.privateApi`.
  **Typing:** collects `SocketBus.typing` into `state.typingChatGuid` (with a 12s
  auto-expiry, since a "stopped" event can be missed); `onComposeTextChanged` sends
  `startTyping` on the first keystroke and `stopTyping` after a 4s pause / empty
  field / send / close — gated on `privateApi`. Keeps a
  session-lived per-conversation `messageCache` (now the *raw* list) so reopening a
  thread is instant (cached shown immediately, fresh fetch refreshes in the
  background; snapshotted on `closeThread`). Starts/stops
  `SocketService`. `sendNewMessage(address,text)` starts a fresh 1:1 via `newChat`
  then opens it; the searchable `contactList` (one `Contact` per address, built
  from `contacts()`) feeds the new-message picker. Screen routing derives from
  state: no password → setup; `composingNew` → new message; `open != null` →
  thread; else list.
- **Socket** (`socket/`) — the live channel.
  - **`SocketService`** — a **`remoteMessaging`** foreground service holding one
    Socket.IO connection (`io.socket:socket.io-client`, websocket transport,
    password as a query param). On `new-message`/`updated-message` it parses via
    `BlueBubblesApi.messageEvent`, pushes onto `SocketBus`, and — when the app
    isn't foreground — posts a notification. The single ongoing socket is the
    whole battery argument vs OpenBubbles' Flutter runtime. (Type is
    `remoteMessaging`, not `dataSync`, specifically because Android 14+ **blocks
    `dataSync` from starting at `BOOT_COMPLETED`** — `remoteMessaging` is allowed
    and is the correct semantic type.)
  - **`BootReceiver`** — restarts `SocketService` on `BOOT_COMPLETED` (if a
    password is stored) so the socket reconnects after a reboot without opening
    the app. BOOT_COMPLETED arrives post-unlock, so encrypted prefs / Keystore
    are available. Needs `RECEIVE_BOOT_COMPLETED`. **Device requirement:** the
    socket can't reach `…ts.net` until Tailscale's tunnel is up, which isn't the
    case at boot unless **Tailscale "Always-on VPN"** is enabled (Android Settings
    → Network → VPN). With it on, the service's reconnect loop connects the moment
    the tunnel comes up (verified: ~15s of `connect error` retries post-boot, then
    `socket connected` the instant Tailscale connected). Leave "Block connections
    without VPN" OFF.
  - **`SocketBus`** — process-wide `MutableSharedFlow`s bridging the service (alive
    even when the activity is dead) to the ViewModel: `incoming` (messages) and
    `typing` (`TypingEvent`, from the socket's `typing-indicator` event).
  - **`AppForeground`** — a volatile flag set by `MainActivity.onStart/onStop` so
    the service only notifies for messages the user isn't already looking at. What it
    can't distinguish is "reading that thread" from "on the list, about to lock the
    phone", so a foreground message isn't dropped, it's deferred — see `PendingAlerts`.
  - **`PackageReplacedReceiver`** — `MY_PACKAGE_REPLACED`, needs no permission: re-arms
    the alarm, re-enqueues the worker and restarts the socket after the app updates
    itself, which otherwise goes quiet until it's next opened by hand.
- **Delivery** (`Delivery.kt`, `PollAlarm.kt`, `CatchUp.kt`, `DeliveryWorker.kt`,
  `PendingAlerts.kt`) — see "Background delivery" above. `Delivery` is the one place that
  knows whether the phone is currently letting background work happen (battery-optimisation
  allowlist → exempt standby bucket) and turns that into the poll interval and the two
  Settings lines. `Store.recordPoll/lastPollAt/lastPollOkAt/pollFailures` is the
  bookkeeping that makes a broken alarm chain *detectable* — a chain with no redundancy
  otherwise fails invisibly.
- **Models** (`Models.kt`) — `Conversation` (carries `guids: List<String>` — every
  chat-room guid it spans, usually just `[guid]`, more for a forked group; `guid` is
  the primary/send target); `ChatMessage` (guid, text, date,
  fromMe, sender, `attachments: List<Attachment>`); `Attachment` (guid, mimeType,
  transferName, width, height — `isImage` gates inline rendering; `typeLabel`/
  `fileLabel` drive the non-image file row). `ChatMessage`
  carries the shared `ATTACHMENT_PLACEHOLDER` (`[Attachment]`) constant; its
  `bodyText` returns null when the text is *only* that placeholder for image(s) we
  draw inline (so an image-only message shows just the image), and `images` is the
  image subset. `previewText` is the conversation-list one-liner: the text, or a
  bracketed attachment summary (`[Photo]` / `[3 Photos]` / `[Attachment]`) when
  there's none — bracketed so it can't be mistaken for literal "photo" text, and used
  wherever `Conversation.lastText` is set so the preview is consistent. **Tapback fields:** `associatedMessageGuid`/`associatedMessageType`
  are set only on reaction messages (`isReaction`, `isReactionRemoval`,
  `reactionTargetGuid` strips iMessage's `p:<n>/`/`bp:` prefixes); `reactions:
  List<Reaction>` is populated by `foldReactions` for display. **Gotcha:** the
  server runs `associatedMessageType` through a transformer, so it arrives as a
  *word* (`love`/`laugh`/…, prefixed `-` for a removal), **not** the raw iMessage
  int (2000/3000/…) — so `ChatMessage.associatedMessageType` is a `String` and
  `ReactionType` keys off that word (its `apiValue`, which doubles as the
  `message/react` reaction param). The visual mark per type is drawn/typeset in
  `ui/Tapbacks.kt`, not stored on the enum. `Reaction` (type, fromMe, sender). `IncomingMessage` (socket payload: chatGuid, message, isNew,
  chatDisplayName); `Contact` (name, address — a pickable recipient for a new message).
- **`Attachments`** (`Attachments.kt`) — the inline-image loader, **dependency-free**
  (no Coil/Glide, matching the house style). `image(context, api, attachment)`
  (suspend, off-main): returns a decoded `ImageBitmap` or null; downloads via
  `BlueBubblesApi.downloadAttachment` (streams `GET /attachment/:guid/download` over
  the same socket), caches the raw bytes under `cacheDir` (so reopening a thread
  doesn't refetch), decodes a **downsampled** bitmap (`inSampleSize` to cap the long
  edge at 1080px — a full-res photo would OOM the phone), applies the **EXIF
  orientation** (BitmapFactory ignores it, so portrait phone photos would otherwise
  render sideways), and holds a small guid-keyed `LruCache`. `cacheLocal(guid,bytes)`
  seeds the cache from a just-picked image so an optimistic outgoing message renders
  through the same path with no round trip. The ViewModel exposes `loadImage`;
  `ThreadScreen`'s `AttachmentImage` loads it lazily via `produceState`, showing
  `[Image]` until ready. **Non-image files** don't use this loader: they render as a
  tappable `AttachmentFile` row (`Type · filename`), and `ViewModel.openAttachment`
  downloads the raw bytes to `cacheDir/shared/` and opens them through the
  `FileProvider` + `ACTION_VIEW`/share intent — no decoding, just a handoff. Sending: `ViewModel.sendImage(uri)` reads the picked bytes
  (the compose bar's "+" launches the system photo picker — no permission), seeds
  the cache, posts an optimistic bubble, then reconciles with the server echo. For a
  brand-new chat there's no guid yet, so `sendNewImage(address, uri)` constructs the
  canonical 1:1 guid `iMessage;-;<handle>` (the address normalized to its E.164/email
  handle by `imessageHandle` — a constructed guid can't lean on `newChat`'s loose
  AppleScript address resolution), sends the attachment to it (which creates the
  chat server-side), then opens the thread + refreshes.
- **`hw/`** — the brightness wheel (`LightKeys.kt`, `Wheel.kt`), the same module the
  sibling apps carry. `LightKeys.of` recognises a notch: `KeyEvent.keyCodeFromString` on
  Light's added `WHEEL_CCW`/`WHEEL_CW` labels first, then the raw scancodes 19/20 gated on
  the sensor's device name (`Pixart pat9126ja`) so a paired keyboard's `r`/`t` can't scroll.
  `MainActivity.dispatchKeyEvent` claims both DOWN and UP — above the view hierarchy, which
  is what lets a notch beat the focused compose bar, and swallowing the UP is what stops the
  wheel typing into a half-written message — and pushes ±1 onto a `WheelBus` that screens
  read through `LocalWheelBus`. Each scroller calls `WheelScroll(state)`: notches accumulate
  as a pixel debt paid down a fraction per frame (the sensor fires faster than a frame, so
  applying each one on arrival is a stack of jumps), and the first notch after a 1.5s pause
  is held until a second confirms it. **`WheelScroll(state, reverse = true)` for the thread**
  — `reverseLayout` reverses the scroll axis with it, so an unflipped notch would walk
  towards older messages while the page appeared to move the other way. `active` gates the
  thread's list while the image viewer or the photo picker is drawn over it (both are
  overlays; the thread stays composed underneath and would otherwise answer the same notch).
  There are no `Dialog`/`ModalBottomSheet` windows in the app, so the template's
  `WheelInDialog` is not carried. `ImageViewerScreen` has no scroller to hoist, so its pan
  offset is wrapped in a hand-made `ScrollableState` — the wheel pans a zoomed photo, which
  dragging does badly here because a drag is also a tap candidate and a tap closes it.
- **Screens** (`ui/`) — `SetupScreen` (password entry), `ConversationsScreen`
  (list, tap title → settings, Refresh, **New**), `NewMessageScreen` (a "To" field
  that searches the contact index by name/number/email or takes a raw address,
  then a compose bar; sends via `newChat` and opens the thread), `ThreadScreen`
  (messages — text + inline images — and a compose bar with a back chevron; `linkify`
  turns http/https URLs in a body into tappable `LinkAnnotation.Url` links),
  `SettingsScreen` (server host + refresh
  + sign out). `ComposeBar` is shared; its optional `onPickImage` adds a leading
  "+" that opens the photo picker — wired in both `ThreadScreen` (sends into the open
  chat) and `NewMessageScreen` (once a recipient is chosen; sends as the first
  message of a new 1:1 via `sendNewImage`); its optional `onTextChange` (thread only)
  drives the typing-indicator sends. `ThreadScreen`'s `TypingIndicator` (an animated
  `•••` just above the compose bar) shows while `state.typingChatGuid` matches the
  open chat. The thread
  `LazyColumn` is **`reverseLayout = true`** with messages newest-first, so it
  opens anchored at the latest (no scroll-to-bottom animation — that whoosh was the
  old bug); scroll *up* for history. A new newest message auto-scrolls down only if
  you're already near the bottom. Thread bubbles-less readability: each turn is
  **width-capped at 80%**
  (`MESSAGE_MAX_WIDTH`) and hugs its side; a name label ("You" / sender) shows
  **only on the first message of a same-speaker run**. **Tapbacks** (`ui/Tapbacks.kt`):
  Public Sans has no heart/thumb/triangle glyphs (they'd fall back to a different
  font — the original bug), so the three iconic tapbacks are drawn as monochrome
  vector paths (`Icon` + parsed Material path data, tinted) and the three text ones
  (haha / ‼ / ?) are typeset in Public Sans — iMessage's own icon+text split, all
  dependency-free. A turn's folded `reactions` render in its empty **gutter**
  (`GutterReactions`) with a small *drawn* arrow pointing back at it (`<- ♥`
  received, `♥ ->` yours — Public Sans lacks the arrow glyphs too, so it's a
  `Canvas` shaft+chevron); same-type reactions collapse to one glyph + count (`♥3`), a type is
  full white if you're among its reactors else 70%, and the row `FlowRow`-wraps when
  many pile up. Long-pressing a turn opens an inline `ReactionPicker` (the six drawn
  marks, your current one bright so re-tapping reads as remove) — only when
  `state.privateApi` is true (`combinedClickable(enabled = canReact)`), since sending
  needs the server's Private API. `Notifications` has two
  channels — high-importance "messages" (per-message) and low "service" (the
  ongoing foreground notification).

## Light Phone III specifics

These look odd out of context but are deliberate, and match a family of sibling
LightOS apps that share the same conventions:

- **UI is black-and-white, Public Sans, text-only** (vandamd's LightOS style).
  Use `ChatColors`, `ChatType`, `ChatDimens` from `ui/theme/LightChatTheme.kt` — not
  Material defaults. "Buttons" are tappable text (`HapticText`). Messages have no
  bubbles — the user's turns right-aligned, others left, width-capped with
  run-collapsed name labels (see the Screens section).
- **Font scale is pinned to 0.85** in `LightChatTheme` (LightOS ships a large default).
- Portrait-only, single activity, `adjustResize`.
- **Full-screen / immersive** — `MainActivity.enableImmersive()` hides the top
  status bar via `WindowInsetsControllerCompat.hide(Type.statusBars())`
  (`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`), re-applied in `onWindowFocusChanged`.
  This is the native equivalent of what vandamd's (Expo) apps do with
  `setStatusBarHidden`. We hide the **status bar only** (not the nav bar) so the
  bottom compose field clears the gesture strip; the same `enableImmersive()` is
  in every sibling app (ask/hive/jot/pod/reel) for a consistent full-screen look.
- **No Google Play dependency anywhere** — that's the whole point, and it's *why*
  we run our own server instead of the official BlueBubbles app (which needs FCM).
  Transport is `HttpURLConnection` (REST) + `io.socket:socket.io-client` (the live
  socket — a plain JVM dep, not a Google one; `org.json` excluded from it since the
  platform provides it). Don't reintroduce Play Services / Firebase.

## The server

A **BlueBubbles Server** on an always-on Mac signed into iMessage, reached over
**Tailscale Serve** at the user-configured `https://<machine>.<tailnet>.ts.net`
URL (entered at setup) — the server stays LAN-bound; Tailscale provides TLS +
private routing, and the live socket (Phase 2) is the push channel, so no FCM is
needed. See the README for the full self-host walkthrough.

**Private API (optional).** `private_api` is off by default. Enabling it lets the
server send tapbacks (and later typing/read receipts) by injecting a helper dylib
into Messages — there's no bundle to install by hand; the server does the
injection once two macOS protections are off: **Library Validation** (`sudo
defaults write /Library/Preferences/com.apple.security.libraryvalidation.plist
DisableLibraryValidation -bool true`) **and SIP** (`csrutil disable` from
Recovery). Then flip the Private API toggle in the server's Settings; its status
box should report the helper connected, and `GET /server/info` returns
`"private_api": true, "helper_connected": true`. The app reads exactly those two
fields (`BlueBubblesApi.serverInfo`) to decide whether to offer tapback sending —
so users who don't want to disable SIP simply never see the picker. README has the
step-by-step. (Library Validation is the step the BlueBubbles docs bury — SIP-off
alone won't let the dylib load.)
