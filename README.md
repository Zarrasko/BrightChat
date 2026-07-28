> ### About this fork
>
> This is [gi-os](https://github.com/gi-os)'s fork of
> **[craigeley/chat](https://github.com/craigeley/chat)**. Craig Eley wrote the app.
> The README below is his, kept as he wrote it.
>
> The fork adds three things, on the
> [`pinned-chats-and-picker-fix`](https://github.com/gi-os/chat/tree/pinned-chats-and-picker-fix)
> branch:
>
> - **Pinned chats.** Long-press a conversation row to pin or unpin it. Pinned rows
>   float to the top under a dim "Pinned" header. The pin stores every room GUID the
>   conversation spans, so a forked group stays pinned when its primary GUID moves.
> - **FaceTime.** The thread header gains a **Call** control. It mints a link through
>   `POST /facetime/session`, sends the link into the chat as the invite, and opens the
>   call in a full-screen WebView on `facetime.apple.com`. Web FaceTime is WebRTC and
>   the system WebView renders it, which matters because the phone has no browser. A
>   FaceTime link inside any message body opens the same screen. Set a FaceTime name in
>   Settings and an injected helper script fills the join form and clicks through, which
>   gets your join request in before the server auto-admit window closes at two minutes.
>   The same script admits guests who arrive after you. Camera and microphone reach only
>   Apple's origin, and only after Android grants them. Closing takes two taps, so a
>   stray tap cannot hang up. Needs the Private API and macOS Monterey or newer.
> - **A fresher photo picker.** The system picker reads MediaStore, which nothing on the
>   Light Phone keeps current, so new camera photos never showed up. A rescan walks DCIM
>   and Pictures for new files and hands them to `MediaScannerConnection` before the
>   picker opens.
>
> The fork also publishes an APK to
> [Releases](https://github.com/gi-os/chat/releases) on every push, signed with a stable
> key so Obtainium can update in place. [CLAUDE.md](CLAUDE.md) documents all of it in
> detail, including the known gaps.
>
> Nothing here is upstream's responsibility. Send bugs in these features to this repo,
> not to Craig.

# Chat

An **iMessage client** for the [Light Phone III](https://www.thelightphone.com/),
inspired by and based on the apps created by [vandamd](https://github.com/vandamd). The app works by talking to an always-on, self-hosted [BlueBubbles Server](https://github.com/BlueBubblesApp/bluebubbles-server), reached privately over [Tailscale](https://tailscale.com/).

I built this to replace OpenBubbles on my LPIII, which I found to be both a battery hog and very flaky in terms of displaying and ordering messages correctly.

Open the app to a list of conversations (newest activity first); tap one to read the
thread, or tap **New** to start one (searches your contacts by name/number/email).
Tap **Messages** at the top for settings, **Refresh** to re-pull.

## Prequisites

There are several things you have to have up and running in order for this to work.

1. A LightPhoneIII modified to reveal the full Android layer. For full instructions on this, I highly recommend fully reading [this guide](https://acrobat.adobe.com/id/urn:aaid:sc:US:0c80fa32-de30-406f-85ca-93ccd92c3c4b).
2. A BlueBubbles server up and running on an always-on Mac, which is signed into your iMessage account.
3. Tailscale installed on your Mac *and* and your modified LPIII.

## Setup

1. **Install BlueBubbles Server**. Install it from
   [bluebubbles.app](https://bluebubbles.app/install/) on a Mac that is signed
   into your iMessage account and stays awake. During setup
   it asks you to set a server password — remember it; the app uses it to
   authenticate.
   
   In the setup steps, you should **skip / ignore** the Google Firebase section entirely, as well as the Proxy Service. You can set that to LAN only. Tailscale (in the next step) will handle it from here.

2. **Put the Mac and the phone on the same tailnet.** Install
   [Tailscale](https://tailscale.com/download) on both and sign them into the
   same account. On the Mac, expose the BlueBubbles port (default `1234`) over
   HTTPS with Tailscale Serve:

   `tailscale serve --bg 1234`

   This gives the Mac a stable `https://<machine>.<tailnet>.ts.net` URL with TLS,
   reachable only from your own devices — no public exposure, no certificates to
   manage. (Any other HTTPS reverse proxy works too; Tailscale Serve is just the
   easiest.) Run `tailscale serve status` to see the URL.

3. Install this app. The best way to do it is to put this url into Obtainium.

4. **Configure the app.** On first launch, enter that `https://…ts.net` URL and
   the BlueBubbles server password. The app validates them against the server and
   stores them on the device.

### Optional: enable the Private API (tapbacks, read receipts, typing)

By default BlueBubbles can only send via AppleScript, which can't send tapbacks,
mark chats read, or send typing indicators. For a full list of features enabled by the private API, see this page. Those need BlueBubbles' **Private
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

### Optional: full-color photo viewing

The Light Phone's grayscale is Android's accessibility color-correction filter,
which apps can lift with a permission only grantable over adb. With it granted,
tapping a photo in a thread shows it in **full color** for exactly as long as
the viewer is open — the phone returns to grayscale the moment you dismiss it
(the same trick as [zero](https://github.com/vandamd/zero)'s red-text mode):

```sh
adb shell pm grant com.craigeley.chat android.permission.WRITE_SECURE_SETTINGS
```

One-time; it survives app updates. Without it, photos simply open in grayscale
like the rest of the phone.

For instant delivery after a reboot without opening the app, enable Tailscale's
**Always-on VPN** on the phone (Android Settings → Network → VPN) and leave
"Block connections without VPN" **off** — the live socket reconnects the moment
the tunnel comes up.

## Install

Install the app via Obtainium.

## License

[MIT](LICENSE).
