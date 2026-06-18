# chat

A from-scratch **iMessage client** for the [Light Phone III](https://www.thelightphone.com/),
in the style of vandamd's LightOS tools (black and white, Public Sans, no bubbles).

It talks to a self-hosted [BlueBubbles Server](https://github.com/BlueBubblesApp/bluebubbles-server)
running on an always-on Mac signed into iMessage, reached privately over
Tailscale. Built to replace OpenBubbles — lighter on battery, and it gets message
ordering right because the client owns the sort.

Sibling of [reel](../reel), [hive](../hive), [jot](../jot), [pod](../pod), and
[ask](../ask).

Open it to a list of conversations (newest activity first); tap one to read the
thread, or tap **New** to start one (searches your contacts by name/number/email).
Tap **Messages** at the top for settings, **Refresh** to re-pull.

## Status

- **Phase 1 (done):** read-only over REST — conversation list + thread view,
  pull-to-refresh, correct ordering, contact names.
- **Phase 2 (done):** sending + a Socket.IO foreground service for live delivery
  and notifications. The working OpenBubbles replacement.
- **Phase 3:** attachments, tapbacks/reactions, read receipts, contact names in
  notifications.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

arm64-only, minSdk 34 (Android 14), target SDK 35.

## How it works

- Talks to the BlueBubbles Server **REST API** over plain `HttpURLConnection` +
  `org.json` — no SDK, no Google Play Services. Auth is the server password.
- A **Socket.IO foreground service** holds one live connection for instant
  delivery and notifications — no FCM. A single lightweight socket, which is the
  whole battery argument over OpenBubbles' Flutter app.
- Reached over **Tailscale Serve** (`https://…ts.net`), so the server stays
  LAN-bound and is never exposed publicly. The official BlueBubbles app needs
  Firebase/FCM for push; running our own server over the tailnet skips that
  entirely (the socket is the push channel).
- The password stays on the device, **encrypted at rest** by a hardware-backed
  AndroidKeyStore AES-GCM key.
- The server URL is hardcoded for one personal server (`Store.BASE_URL`).
