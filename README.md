# chat

A from-scratch **iMessage client** for the [Light Phone III](https://www.thelightphone.com/),
in the style of vandamd's LightOS tools (black and white, Public Sans, no bubbles).

It talks to a self-hosted [BlueBubbles Server](https://github.com/BlueBubblesApp/bluebubbles-server)
running on an always-on Mac signed into iMessage, reached privately over
[Tailscale](https://tailscale.com/). Built to replace OpenBubbles — lighter on
battery, and it gets message ordering right because the client owns the sort.

Open it to a list of conversations (newest activity first); tap one to read the
thread, or tap **New** to start one (searches your contacts by name/number/email).
Tap **Messages** at the top for settings, **Refresh** to re-pull.

## How it works

- Talks to the BlueBubbles Server **REST API** over plain `HttpURLConnection` +
  `org.json` — no SDK, no Google Play Services. Auth is the server password.
- A **Socket.IO foreground service** holds one live connection for instant
  delivery and notifications — no FCM. A single lightweight socket is the whole
  battery argument over OpenBubbles' Flutter app.
- The server is reached over your **tailnet**, so it stays LAN-bound and is never
  exposed publicly. The official BlueBubbles app needs Firebase/FCM for push;
  running your own server over Tailscale skips that entirely (the socket is the
  push channel).
- The server URL and password are entered on first launch and stored on the
  device only — the password **encrypted at rest** by a hardware-backed
  AndroidKeyStore AES-GCM key. Both are editable later in Settings.

## Set up your own server

You need three things: a Mac signed into iMessage, BlueBubbles Server on it, and
Tailscale to reach it privately from the phone.

1. **Run BlueBubbles Server on an always-on Mac.** Install it from
   [bluebubbles.app](https://bluebubbles.app/install/) on a Mac that is signed
   into your iMessage account and stays awake (a Mac mini is ideal). During setup
   it asks you to **set a server password** — remember it; the app uses it to
   authenticate. The server stays bound to localhost/LAN; you do not need to
   forward any ports or expose it to the internet.

2. **Put the Mac and the phone on the same tailnet.** Install
   [Tailscale](https://tailscale.com/download) on both and sign them into the
   same account. On the Mac, expose the BlueBubbles port (default `1234`) over
   HTTPS with Tailscale Serve:

   ```sh
   tailscale serve --bg 1234
   ```

   This gives the Mac a stable `https://<machine>.<tailnet>.ts.net` URL with TLS,
   reachable only from your own devices — no public exposure, no certificates to
   manage. (Any other HTTPS reverse proxy works too; Tailscale Serve is just the
   easiest.) Run `tailscale serve status` to see the URL.

3. **Configure the app.** On first launch, enter that `https://…ts.net` URL and
   the BlueBubbles server password. The app validates them against the server and
   stores them on the device.

### Optional: enable the Private API (tapbacks, read receipts, typing)

By default BlueBubbles can only send via AppleScript, which can't send tapbacks,
mark chats read, or send typing indicators. Those need BlueBubbles' **Private
API**, which injects a helper into Messages — and that requires turning off two
macOS protections. It's optional; skip this and everything else still works.

1. **Disable Library Validation** (lets the helper load into Messages):

   ```sh
   sudo defaults write /Library/Preferences/com.apple.security.libraryvalidation.plist DisableLibraryValidation -bool true
   ```

2. **Disable System Integrity Protection (SIP).** Boot into Recovery
   (Apple Silicon: hold the power button → *Options*; Intel: hold ⌘R at boot),
   open Terminal, run `csrutil disable`, then reboot. Verify with `csrutil status`
   — it should read `disabled`. (On Apple Silicon this also disables running iOS
   apps on the Mac.) Do this at your own risk; a VM snapshot first is wise.

3. **Flip it on in the server.** BlueBubbles Server → *Settings* → **Private API**
   toggle on. There's no bundle to install by hand — the server injects the helper
   itself. Hit refresh on the **Private API Status** box; it should report the
   helper connected. (`GET /api/v1/server/info` then shows `"private_api": true`
   and `"helper_connected": true` — the app reads this to decide whether to offer
   tapbacks etc.)

For instant delivery after a reboot without opening the app, enable Tailscale's
**Always-on VPN** on the phone (Android Settings → Network → VPN) and leave
"Block connections without VPN" **off** — the live socket reconnects the moment
the tunnel comes up.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # JDK 21 required
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

arm64-only, minSdk 34 (Android 14), target SDK 35. Built for the Light Phone III;
other Android 14+ devices should work but are untested.

## Status

- **Phase 1 (done):** read-only over REST — conversation list + thread view,
  pull-to-refresh, correct ordering, contact names.
- **Phase 2 (done):** sending + a Socket.IO foreground service for live delivery
  and notifications. The working OpenBubbles replacement.
- **Phase 3 (in progress):** image attachments (send + receive), contact names in
  notifications, **tapbacks** (incoming render compactly; long-press a message to
  send your own), and **marking threads read** — both need the optional Private API,
  above. Still to come: typing indicators, non-image attachments.

## License

[MIT](LICENSE).
