# chat

An iMessage client for the [Light Phone III](https://www.thelightphone.com/). It talks
to a self-hosted [BlueBubbles](https://github.com/BlueBubblesApp/bluebubbles-server)
server on an always-on Mac, reached privately over [Tailscale](https://tailscale.com/).

This is a fork of **[craigeley/chat](https://github.com/craigeley/chat)**.

Part of the [gi-os Light App collection](#the-gi-os-light-app-collection).

The app exists to replace OpenBubbles, whose Flutter client drains the battery and
orders messages wrong. A thin native client over one socket on the tailnet is far
lighter, and it gets the order right because it owns the sort.

Open the app to a conversation list, newest first. Tap a thread to read it. Tap **New**
to start one, which searches your contacts by name, number or email. **Messages** at the
top opens settings. **Refresh** re-pulls.

## What it does

- Reads and sends text over the BlueBubbles REST API, with an optimistic local echo that
  the server echo then reconciles.
- Holds a Socket.IO foreground service open for live delivery and notifications, and
  restarts it at boot.
- Receives and sends image attachments. The system photo picker feeds a multipart
  upload.
- Renders non-image attachments as a tappable `Type - filename` row, downloads the file,
  and hands it to an external app through a `FileProvider`.
- Folds incoming tapbacks onto the message they target instead of giving them a row.
  Long-press a message to send your own. Tap the same one again to remove it.
- Marks a thread read on your other devices when you open it.
- Shows typing indicators both ways in a one-to-one thread.
- Shows delivery receipts, group-event rows, unread markers and reply threading.
- Merges forked group chats into one conversation, and sends to a forked group through
  AppleScript.
- Swipe a conversation to delete it.
- Resolves a sender address to a contact name inside a notification, because the socket
  service persists the contact index and runs with no activity alive.
- Opens a photo full screen on tap. Pinch to zoom to 4x, drag to pan, double-tap to
  toggle. The viewer draws on top of the thread, so closing it returns you to the same
  scroll position.

## Requirements

You need three things before any of this works.

1. A Light Phone III with the full Android layer revealed.
2. A BlueBubbles server on an always-on Mac, signed into your iMessage account.
3. Tailscale on both the Mac and the phone.

## Setup

1. **Install the BlueBubbles server** from
   [bluebubbles.app](https://bluebubbles.app/install/) on the Mac. Set a server password
   and remember it. Skip the Google Firebase section and the Proxy Service. Set the
   proxy to LAN only. Tailscale covers the rest.

2. **Put the Mac and the phone on one tailnet.** Install
   [Tailscale](https://tailscale.com/download) on both and sign both into the same
   account. On the Mac, expose the BlueBubbles port over HTTPS:

   ```sh
   tailscale serve --bg 1234
   ```

   That gives the Mac a stable `https://<machine>.<tailnet>.ts.net` address with TLS,
   reachable only from your own devices. Run `tailscale serve status` to read it. Any
   other HTTPS reverse proxy works too.

3. **Install the app.** Add `https://github.com/gi-os/chat` to
   [Obtainium](https://github.com/ImranR98/Obtainium).

4. **Configure it.** On first launch, enter the `ts.net` address and the server
   password. The app checks them against the server and stores them on the device.

## Optional: the Private API

By default BlueBubbles sends through AppleScript, which cannot send a tapback, mark a
chat read or send a typing indicator. Those need the BlueBubbles Private API, which
injects a helper into Messages. That requires turning off two macOS protections. Skip
this step and everything else still works.

1. Turn off Library Validation, so the helper can load into Messages:

   ```sh
   sudo defaults write /Library/Preferences/com.apple.security.libraryvalidation.plist DisableLibraryValidation -bool true
   ```

2. Turn off System Integrity Protection. Boot into Recovery. On Apple Silicon, hold the
   power button and choose **Options**. On Intel, hold Command-R at boot. Open Terminal,
   run `csrutil disable`, then reboot. Check with `csrutil status`. On Apple Silicon this
   also stops iOS apps from running on the Mac. Take a snapshot first.

3. Turn on **Private API** in BlueBubbles Server settings. The server injects the helper
   itself. Refresh the **Private API Status** box until it reports the helper connected.

The app reads `GET /api/v1/server/info` and only offers tapbacks when both `private_api`
and `helper_connected` come back true. Incoming reactions render either way.

## Optional: color photos

The Light Phone grayscale is the Android accessibility color-correction filter. An app
can lift it with a permission that only adb can grant. With the permission, a photo
opens in full color for as long as the viewer stays open, and the phone returns to
gray the moment you close it.

```sh
adb shell pm grant com.craigeley.chat android.permission.WRITE_SECURE_SETTINGS
```

Grant it once. It survives app updates. Without it, photos open gray like everything
else.

For instant delivery after a reboot without opening the app, turn on Tailscale
**Always-on VPN** in Android network settings and leave **Block connections without
VPN** off. The socket reconnects as soon as the tunnel comes up.

## Origin and credits

- **[craigeley](https://github.com/craigeley)** wrote
  [chat](https://github.com/craigeley/chat). The BlueBubbles API client, the socket
  service, the conversation and thread screens, the attachment pipeline, the tapback
  folding and the notification handling are his work. This fork tracks his `develop`
  branch. Thank you.
- **[vandamd](https://github.com/vandamd)** wrote the Light Phone apps that set the
  visual language craigeley built against, and [zero](https://github.com/vandamd/zero)
  (MIT), which is where the grayscale lift in the full-color image viewer comes from.
  The technique itself is the stock Android color-correction setting, and
  [garado/light-topographic](https://github.com/garado/light-topographic) ships it too.
  The legacy PNG app icon follows vandamd's convention as well.
- **[The BlueBubbles team](https://github.com/BlueBubblesApp)** built the server and the
  Private API. None of this exists without it.
- **[Tailscale](https://tailscale.com/)** carries the traffic, so the Mac never faces
  the public internet.
- **[The Light Phone](https://www.thelightphone.com/)** for the hardware and for LightOS.
- **[Obtainium](https://github.com/ImranR98/Obtainium)** by ImranR98 handles updates.

The same grayscale trick runs in [LightPass](https://github.com/gi-os/LightPass), which
lifts the filter while a movie ticket is on screen.

## The gi-os Light App collection

Eight tools for the Light Phone III, all open source, all built in one run.

| Tool | What it does | Built on |
| --- | --- | --- |
| [LightPass](https://github.com/gi-os/LightPass) | Photograph a movie ticket, keep the stub | Plain Android |
| [LightQR](https://github.com/gi-os/LightQR) | QR scanner, plus a browser generator | Plain Android |
| [LightRSS](https://github.com/gi-os/LightRSS) | RSS and Atom reader with images and QR subscribe | light-sdk, fork of [zachattack323/LightRSS](https://github.com/zachattack323/LightRSS) |
| [LightNYCSubway](https://github.com/gi-os/LightNYCSubway) | Live MTA subway arrivals | light-sdk fork |
| **chat** (this repo) | iMessage over a self-hosted BlueBubbles server | Fork of [craigeley/chat](https://github.com/craigeley/chat) |
| [LightFog](https://github.com/gi-os/LightFog) | Fog of World companion, GPS recorder and fog map | Fork of [garado/light-topographic](https://github.com/garado/light-topographic) |
| [LightNonogram](https://github.com/gi-os/LightNonogram) | Picross, plus a generator that only ships solvable puzzles | Kotlin generator, light-sdk tool |
| [LightSolitaire](https://github.com/gi-os/LightSolitaire) | Klondike, draw one, unlimited redeals | light-sdk |

The Light Phone does not sponsor or endorse any of these.

## License

MIT, the same as upstream. See [LICENSE](LICENSE).
