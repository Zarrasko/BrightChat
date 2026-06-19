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
  plain REST. Tapbacks/reactions, typing, and read-receipt *sending* remain gated on
  the server's Private API (SIP disabled + helper bundle on the Mac, see The server).
  **Not yet done:** non-image attachment types (video/audio/vcard still show
  `[Attachment]`).

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

On first launch the app asks for the **BlueBubbles Server password**, validates it
against `GET /api/v1/server/info`, and stores it encrypted on the device. The
server URL is hardcoded (`Store.BASE_URL`). If the API returns 401/403 the app
clears the password and returns to setup.

## Architecture

- **`Store`** (`api/Store.kt`) — SharedPreferences. `BASE_URL` is **hardcoded**
  (one personal server). The password is encrypted at rest via `SecureStore`. Also
  persists the **contact index** (`setContacts`/`contacts`) as the normalized
  key→name map (JSON) so `SocketService` can name notification senders without the
  app running; the ViewModel writes it on each contacts load, `signOut` wipes it
  with everything else.
- **`SecureStore`** (`api/SecureStore.kt`) — at-rest encryption only. An
  AES-256-GCM key lives non-exportable in the AndroidKeyStore (hardware-backed)
  and encrypts the password. (Trimmed down from `ask`'s version — no Ed25519 /
  Bouncy Castle here; BlueBubbles auth is just the password.)
- **`BlueBubblesApi`** (`api/BlueBubblesApi.kt`) — the REST surface, plain
  `HttpURLConnection` + `org.json` (no networking dependency, like hive/pod).
  Auth is the server password as the `password` query param on every call.
  `validate()` → `GET /server/info`; `messages(guid)` → `GET /chat/:guid/message`
  (`with=handle,attachment`, `sort=DESC`, guid URL-encoded); `send(guid,text,tempGuid)`
  → `POST /message/text` (only `chatGuid`+`message` required; we pass a `tempGuid`
  to correlate the echo and `method:"apple-script"` since Private API is off, and
  parse the created message from the response);
  `sendAttachment(guid,bytes,name,mime,tempGuid)` → `POST /message/attachment` (the
  one **multipart/form-data** call — built by hand, not via `request()` — same
  `tempGuid`/`apple-script` echo handling as `send`); `downloadAttachment(guid,dest)`
  → `GET /attachment/:guid/download` (streams the raw bytes to a file, for the inline
  image loader); `newChat(address,text)` →
  `POST /chat/new` (starts a 1:1 by sending the first message — macOS Big Sur+
  requires the message inline; returns the new chat guid); `contacts()` → `GET /contact`
  flattened to (address,name) pairs. Message parsing lives in the **companion**
  (`parseMessage`, `messageText`, `messageEvent`) so the socket service decodes
  `new-message`/`updated-message` payloads with the same logic. `ApiException.isAuthError`
  flags 401/403. Timestamps are epoch millis, carried through unchanged.
  - **`conversations()` deliberately does NOT use `chat/query`.** That endpoint
    sorts by an unreliable `lastmessage` cache and often returns an empty
    `lastMessage`, so on this account (~2700 chats) a freshly-active chat can fall
    outside its first page and never appear, and the list shows stale ordering
    (this was the "stuck at 4 hours ago" bug). Instead we drive the list from one
    `POST /message/query` DESC sweep (`with:[chats,chats.participants]`, the newest
    ~1000 messages) and take the first message seen per chat — already in true
    recency order, with each chat's metadata read off the embedded chat object.
    Trade-off: only chats active within the sweep window appear (the recent ones —
    what a messages list shows). **1:1 chats (style 45) return empty
    `participants`; the `chatIdentifier` is the other party's address, so we fall
    back to it** — otherwise 1:1 rows render as "Unknown".
- **`ChatViewModel`** — single source of truth (`UiState` as a `StateFlow`).
  Drives `Idle → Loading → Ready/Error`. Owns password setup, the conversation
  list, the open thread, sending, and the live feed. **Ordering is enforced
  here** — conversations `sortedByDescending { lastDate }`, messages
  `sortedBy { date }` — which is the whole reason this exists. `sendMessage()`
  appends an optimistic message under a temp guid, then swaps in the server's
  echo (real guid) so the socket's `new-message` dedupes by guid. It collects
  `SocketBus` and folds incoming/updated messages into the list (`bumpConversation`)
  and the open thread (`mergeMessage`, dedupe by guid); a message for an unknown
  chat triggers a `refresh()`. Keeps a session-lived per-conversation
  `messageCache` so reopening a thread is instant (cached shown immediately, fresh
  fetch refreshes in the background; snapshotted on `closeThread`). Starts/stops
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
  - **`SocketBus`** — a process-wide `MutableSharedFlow<IncomingMessage>` bridging
    the service (alive even when the activity is dead) to the ViewModel.
  - **`AppForeground`** — a volatile flag set by `MainActivity.onStart/onStop` so
    the service only notifies for messages the user isn't already looking at.
- **Models** (`Models.kt`) — `Conversation`; `ChatMessage` (guid, text, date,
  fromMe, sender, `attachments: List<Attachment>`); `Attachment` (guid, mimeType,
  transferName, width, height — `isImage` gates inline rendering). `ChatMessage`
  carries the shared `ATTACHMENT_PLACEHOLDER` (`[Attachment]`) constant; its
  `bodyText` returns null when the text is *only* that placeholder for image(s) we
  draw inline (so an image-only message shows just the image), and `images` is the
  image subset. `IncomingMessage` (socket payload: chatGuid, message, isNew,
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
  `[Image]` until ready. Sending: `ViewModel.sendImage(uri)` reads the picked bytes
  (the thread compose bar's "+" launches the system photo picker — no permission),
  seeds the cache, posts an optimistic bubble, then reconciles with the server echo.
- **Screens** (`ui/`) — `SetupScreen` (password entry), `ConversationsScreen`
  (list, tap title → settings, Refresh, **New**), `NewMessageScreen` (a "To" field
  that searches the contact index by name/number/email or takes a raw address,
  then a compose bar; sends via `newChat` and opens the thread), `ThreadScreen`
  (messages — text + inline images — and a compose bar with a back chevron),
  `SettingsScreen` (server host + refresh
  + sign out). `ComposeBar` is shared; its optional `onPickImage` adds a leading
  "+" that opens the photo picker — passed only in `ThreadScreen` (a brand-new chat
  has no guid to attach to yet), so `NewMessageScreen` stays text-only. The thread
  `LazyColumn` is **`reverseLayout = true`** with messages newest-first, so it
  opens anchored at the latest (no scroll-to-bottom animation — that whoosh was the
  old bug); scroll *up* for history. A new newest message auto-scrolls down only if
  you're already near the bottom. Thread bubbles-less readability: each turn is
  **width-capped at 80%**
  (`MESSAGE_MAX_WIDTH`) and hugs its side; a name label ("You" / sender) shows
  **only on the first message of a same-speaker run**. `Notifications` has two
  channels — high-importance "messages" (per-message) and low "service" (the
  ongoing foreground notification).

## Light Phone III specifics

These look odd out of context but are deliberate, and match the siblings (see
`~/Developer/ask/CLAUDE.md` for the fuller version):

- **UI is black-and-white, Public Sans, text-only** (vandamd's LightOS style).
  Use `ChatColors`, `ChatType`, `ChatDimens` from `ui/theme/ChatTheme.kt` — not
  Material defaults. "Buttons" are tappable text (`HapticText`). Messages have no
  bubbles — the user's turns right-aligned, others left, width-capped with
  run-collapsed name labels (see the Screens section).
- **Font scale is pinned to 0.85** in `ChatTheme` (LightOS ships a large default).
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

A **BlueBubbles Server** (v1.9.9 at time of writing) on an always-on Mac
(`fieldmac-mini`), reached over **Tailscale Serve** at the hardcoded
`https://…ts.net` URL — the server stays LAN-bound; Tailscale provides TLS +
private routing, and the live socket (Phase 2) is the push channel, so no FCM is
needed. `private_api` is off (so no tapbacks/typing from the server yet — that's
a Phase 3 prerequisite, needs SIP disabled + the helper bundle on the Mac).
