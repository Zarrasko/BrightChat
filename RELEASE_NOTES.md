## LightChat v2.9 — Backups, and a smaller app

**LightSync can now back this app up, the wheel code is shared with every other app instead of
copied into this one, and the release build is shrunk harder.**

### LightSync can back up your setup — but not your password

LightChat now answers LightSync. What travels is the small pile of things that exist only on this
phone: the server URL, your pinned and favourited conversations, the speed-dial slots, the
"notify me about unknown senders" setting, the cached contact index, and which conversations you
have already opened a note for. Rebuilding that by hand after a wipe was an evening's work.

**The BlueBubbles password is not in the backup, and you will have to type it again after a
restore.** This is deliberate. The password is encrypted by a key held in the phone's secure
element, which by design cannot be copied off the device and does not survive a factory reset.
Backing up the encrypted blob would produce a file that restores perfectly and then decrypts to
nothing — a backup that looks like one and is not. The honest version is the one shipped here: the
server URL comes back, the password field is empty, and Setup asks for it once. Exporting the
password in plaintext instead was the alternative and was not worth it to save one typed field.

**Your messages are not in the backup either**, and this one costs nothing. Every message the
phone holds is a copy of something the BlueBubbles Server still has; the local database exists so
the list opens instantly and works offline, not because it is the only copy. Backing it up would
mean carrying megabytes to a new phone that the first sync fetches anyway — and carrying them
stale. The one thing in this app that genuinely cannot be re-fetched is the note on a contact
page, and that note lives in LightNotebook, which backs it up itself.

Downloaded photos and video are excluded for the same reason, more so: they are the biggest thing
on disk and every one of them can be fetched again.

### The wheel is library code now

`com.gios.lightchat.hw` is gone. The two files in it — the key recogniser and the smoothed
scroller — are now `com.gios:light-common:1.2.0`, the same copy every other app uses. Nothing
about scrolling changes: same 64dp notch, same two-notch guard against a stray brush of the thumb,
same reversed direction in a thread so turning the wheel up still walks back through the
conversation rather than away from it. That reversal is in the library specifically because this
app needed it.

The point of the move is that the wheel used to be a file pasted into ten repositories, and the
copies had already drifted. A fix now lands everywhere at once.

### The release build is shrunk harder

R8 full mode is on. It merges classes, drops arguments nothing reads, and assumes a class it never
sees allocated is never built — none of which the default mode attempts. On a phone this slow to
cold-start that is worth having. The baseline profile that ships inside light-common is also
actually applied now, which it was not before: below Android 12 nothing on the device reads a
profile unless the app brings the installer with it.

Shrinking this aggressively breaks things quietly, so the risk is worth naming. The rules added
for it each say which mechanism they protect, and one was a real bug waiting to happen: tapbacks
are stored in the local database by the *name* of the reaction, so R8 renaming that name would
have made every cached tapback vanish — not on install, but on the next update, and only for
messages received before it. Alarms, the socket service, the boot receiver and the two providers
other apps read are pinned for the same class of reason.

If something does break in a way that only happens on a real build and not in debug, that is the
suspect, and reverting `android.enableR8.fullMode` in `gradle.properties` is the one-line test.
