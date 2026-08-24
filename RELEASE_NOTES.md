## BrightChat v2.27 — colour that loses a race to LightOS now wins the rematch

**"Color filter did not work until i shook to submit this feedback."** Opening a photo is supposed
to lift the phone's forced grayscale for as long as the photo is on screen. On this launch it
didn't — and shaking the phone to file the report is what fixed it, which is the bug in one
sentence: the report activity swapping in and out re-ran the app's start/stop pair, and *that*
re-applied the colour lift that the first attempt had lost.

Lost to what? LightOS pins the grayscale itself (the accessibility daltonizer), and it writes that
setting around app handoffs. The old `ColorMode` wrote its side **on transitions only** — once when
the first screen asked for colour, once when the last one left. A one-shot write that lands before
LightOS's does is simply overwritten, and nothing in the app would ever write again: the screen
count never re-crossed zero, so the phone sat in black and white with a photo open, looking exactly
like the missing adb grant. Worse, the old lift trusted a *read* of the setting — if the daltonizer
happened to be off for the instant it looked (as it is mid-handoff), it concluded "already colour",
recorded nothing, and the restore on the way out had nothing to put back.

BrightMusic hit this same failure in its v0.41 (this very file was the ancestor) and the fix ports
straight back: stop writing on edges, state the goal. One `apply()` computes what the panel should
be *right now* — some screen wants colour **and** the app is in the foreground — and makes it so,
called from every entry point: screen opened, screen closed, app hidden, app shown. A write that
LightOS clobbers is re-issued by the next call instead of being lost forever, and what to restore is
remembered from what we ourselves changed, never guessed from a read that can lie. Leaving the app
still returns the phone to black and white immediately — that part was always right.

Fixes [light-reports#35] — colour did not engage until shaking to report forced an activity swap.

- 96 tests.

---

## BrightChat v2.26 — it is a microphone, and it only shows up when it works

**"Should be a mic not a voice button."** It is now a drawn microphone — a capsule, a cradle, a stem
and a foot — filled while it is listening, outlined while it is not.

Drawn, and not written, for the third time of asking. It was `◉` first, which Public Sans has no
glyph for, so the key rendered as nothing at all and the feature was reported missing. Then it was
the word "Speak", which could at least be seen. A picture this time, but not a font's picture: there
is no typeface on this phone whose microphone can be relied on, and 🎤 is a color emoji on a
monochrome panel, which would arrive as a grey blob. Four drawn primitives always draw.

Worth recording how it was checked, since a drawn icon either looks right or looks like a mistake:
the geometry was rendered outside the app and looked at before any of it went into the phone. The
first attempt put the stem at the *center* of the cradle arc rather than its lowest point, which
draws a trident. That would have shipped.

**"Do not show the voice button unless it is turned on."** It is hidden again until a transcription
server is configured, which reverses the call made in v2.23 — and the reversal is right. The
reasoning then was that a hidden key teaches nobody anything; what that missed is that a key which
cannot work teaches the wrong thing, and the actual fix for "I could not find the setting" was
v2.25's settings page you can reach. A microphone that only ever apologizes is worse than no
microphone.

It appears and disappears the moment the setting changes, rather than the next time the app is
restarted. Whether transcription is set up now lives in the app's state and is republished whenever
a field is committed or a QR code is scanned — reading it off disk as each screen composed is what
made the key stale before.

- 96 tests.

---

## BrightChat v2.25 — one settings page, and it scrolls

**"Build out a full app settings page, it's a little hard to access everything."** It was hard for
two reasons, and the first one is a bug.

### Half the settings were off the bottom of the screen

The page was a plain column with a spacer at each end. That centres a short page nicely, and clips a
long one — and the page got long: unknown senders, on-screen alerts, announce calls, your own number,
transcription. Everything past the bottom of the panel was simply unreachable, **Sign out included**.
There was no scroll bar to find, so there was nothing to tell you anything was missing.

It scrolls now, and the wheel scrolls it, because a thumb on this screen covers the line it is trying
to read.

### And it has sections

Server · Messages · Calls · Newsletters · Agents · Transcription · Delivery · Account. The page is
read by scanning for the headings, so one place decides how far apart they sit rather than a spacer
per setting that drifts as things are added. Every setting says, in the grey line underneath, what
the state it is currently in actually means.

### Newsletters is in Settings now

It was reachable only by starting a new message and finding a line at the foot of the recipient
picker. Nobody would look there for a saved list of people to write to, so it is linked from Settings
as well, with the number of saved batches next to it. (Speed dial stays on the Dial tab, with the
keypad it belongs to.)

## Scan the Whisper API key off another screen

**"Add QR code input for Whisper — the API key, that is."** Fifty-odd characters of case-sensitive
base62 is a bad thing to type on any phone, and a worse one here.

Settings → Transcription → **Scan a QR code**. It reads three shapes, in order of how much they say:

- **A bare key.** `qrencode` over the key you just pasted from a dashboard, which is what people
  actually have. This is the case the feature exists for.
- **A URL**, taken as the server — and `?api_key=…&model=…` off the query if it carries them, with
  the query stripped from what gets stored.
- **A JSON object**, for the whole setup in one code: `url`, `key`, `model`, with `base_url`/`api_key`
  also accepted so one generator can make both this and an agent code.

Scanning something that is neither — a poster, a wifi code, a sentence — says so rather than storing
the words and failing every request afterwards with no explanation. What landed is named back at you
("Scanned: key"), because a code carrying only a key looks exactly like one carrying nothing until
the next transcription fails.

The camera, the decode and the permission flow are the ones the agent scanner already used: ZXing
compiled in, no Google Play Services, nothing leaves the phone.

## The screen stays on while you talk to it

**"Also keep the screen on with voice to text."** Speaking is the one thing you do on this phone with
nothing to touch, so the display timeout has no idea you are still there and the panel goes dark
mid-sentence. The recording survives that — MediaRecorder does not care about the screen — but you
are then talking at a black phone with no way to tell whether it is still listening.

The screen is now held while the microphone is open, and held again while a transcription is in
flight: a whole audio file going up to a Whisper server and a model run over it can easily outlast
the display timeout, whether the words came from the Speak key or from a clip somebody sent you.

Under it, the newsletter hold from v2.24 was rebuilt as one shared thing rather than a window flag in
the activity. It counts its holders, which matters more than it sounds: stopping a dictation makes it
"not listening" and "transcribing" in the same frame, and with a plain flag the first of those to
finish would have turned the screen off on the other. It is the view's own `keepScreenOn` — no
permission, and the platform drops it with the view, so there is no path where this is left holding
the screen with nothing running.

- 96 tests, nine of them new and all about what a scanned code is allowed to mean.

---

## BrightChat v2.24 — the screen stays on while a newsletter goes out

**A broadcast takes a while, and the panel used to go dark in the middle of it.**

A newsletter is one send per recipient down a single tunnel to a Mac, deliberately in sequence so
they arrive in order and do not compete for the socket. Twenty recipients is a minute or two, not a
moment — and on a phone whose display timeout is short by design, the screen went out while it was
still going. The only way to find out how far it had got was to wake the phone and hope the progress
line was still there.

The screen now stays awake for as long as `Sending 7/20…` is true, and lets go the moment it is not.

**It also protects the send itself**, which is worth knowing. The send was never cancelled by the
screen going off — it runs in the view model's scope, which outlives the display — but with the
screen off the app is a background process, and a background process on a phone this size can be
reclaimed to free memory. Two minutes is long enough for that to happen. Holding the screen on keeps
the app in the foreground for the duration.

A window flag rather than a wake lock: no permission, and the system takes it back by itself when the
activity goes away, so there is no path where this is left holding the screen on with nothing
sending.

Scoped to the newsletter and not to every upload, on purpose. A photo takes a second or two and
holding the screen on for that would cost battery all day for nothing. A broadcast is the one send
long enough to be worth watching.

- 87 tests.

---

## BrightChat v2.23 — the Speak key, where you can see it

**"No mic button."** Three reasons it could have been missing, and I have fixed all three rather
than guess which one it was.

### It was hidden unless a server was configured

The most likely one. The key only appeared once a transcription server was set in Settings, on the
reasoning that a key which cannot work is worse than no key. That was wrong: a hidden key teaches
nobody anything, and the person most likely to be missing the setting is the person who just asked
for the feature. It is always there now, and pressing it with nothing configured says what to do
about it.

The setting is also read **when the key is pressed** rather than when the screen is composed. Before,
setting a server and returning to a thread that never left composition left the key still believing
there was none.

### It was in one place out of three

Dictation was in a conversation only. A new message and an agent thread are both places you type and
neither had it. All three now do, and the plumbing lives in one function rather than three copies —
the part that must not diverge is releasing the recorder.

### It was a symbol instead of a word

The key was labelled `◉`. A glyph the font does not have renders as nothing at all, so an invisible
key is indistinguishable from a feature that was never built. It says **Speak**, and **Stop** while
it is listening — which matches "Send" beside it and needs no explaining.

Worth being straight about: this was a hypothesis, and writing a test for it proved it partly wrong.
The app already ships `×`, `•`, `‹`, `−`, `↑` and `⌫` as labels and has for many releases, so the
font is clearly not the problem it looked like. What had no precedent was the Geometric Shapes block,
where `◉` lives — so that is what the test forbids, and nothing wider.

- 87 tests, up from 86.

---

## BrightChat v2.22 — dictate a message

**Speak instead of typing.** Tap the ring beside Send, say the message, tap again — the words appear
in the field.

The keyboard is the hardest part of using this phone, and the app was already configured to talk to
a Whisper server for the sake of reading somebody else's voice memo. The same endpoint turns your own
voice into a message, which is the more useful direction of the two: you read a memo occasionally
and you type every day.

**A tap, not a hold.** A message is longer than a thumb wants to be held down for, and letting go by
accident half way through a sentence would lose the sentence.

**The words are appended, not substituted.** A sentence can be half typed and half spoken, and a
mis-heard dictation does not throw away the part that came out right.

### What it records, and what happens to it

AAC in an `.m4a` at 16 kHz mono, which is not a taste: 16 kHz is what Whisper resamples everything
to internally, so recording higher makes a bigger file for the same transcript, and a minute of
speech compressed is about 120 kB against 1.9 MB uncompressed — on a phone tethered over a tunnel
that is the difference between a pause and a wait. The source is `VOICE_RECOGNITION`, which is the
one the platform points at speech.

The recording is **deleted the moment it comes back as words**, whether it worked or not. It is a
draft of a message, not a message, and there is nothing about it worth keeping. Nothing is recorded
unless your thumb has started it, and the key is not there at all unless you have set a
transcription server — so this feature exists exactly to the extent that it can work.

The recorder is released on every path out, including the failures and including leaving the thread
mid-dictation. `MediaRecorder` holds a hardware encoder and this phone has few; one leaked by an
exception is a microphone no other app can open until the process dies.

- 86 tests.

---

## BrightChat v2.21 — a voice memo you can read

**Whisper transcription. Open a sound somebody sent, press WORDS, and read what was said.**

Useful for the thing a voice memo is worst at: a name you did not catch, a street, a number, an
address rattled off at speed. The words appear under the transport, because listening is what you
came to do and reading is what you do when listening was not enough.

### It runs on a server, not on the phone

Whisper on-device means `whisper.cpp` through the NDK plus a model file. The smallest useful model
quantises to about thirty megabytes — most of an APK sideloaded onto a phone whose whole premise is
being small — and the tiny model is the one that mishears exactly the names you were trying to
catch. Against that, this phone is already talking to a machine over Tailscale to get its messages
at all. So the machine with the CPU does the work and the phone sends it a file.

**Point it at whatever you have.** Settings takes a URL, an optional key and a model name, and the
endpoint is OpenAI's `/v1/audio/transcriptions` — which is a *shape* rather than a vendor.
`whisper.cpp`'s own server, `faster-whisper-server`, LM Studio and OpenAI itself all answer the same
request. That is the same reasoning the agent settings are built on, and the reason nothing ships in
the APK.

A URL and not a switch, because there is nothing to switch on: transcription exists exactly to the
extent that you have pointed it somewhere. Blank is the honest off, and the WORDS key is not there
when it is blank.

### The details that matter

- **The file is streamed, not assembled.** Multipart by hand over `HttpURLConnection`, same as every
  other client here — and a ten-minute recording is twenty-six megabytes, which is not a
  `ByteArray` this phone will hand out.
- **Four-minute read timeout.** A few minutes of audio on a CPU-only server genuinely takes a
  minute, and the usual timeout gives up in the middle of the answer.
- **Transcripts are cached** against the attachment's guid, so a memo opened twice is transcribed
  once. Against a paid endpoint that is the difference between a feature and a bill. Keyed on the
  guid and not the file, because the file is a cache entry that can be evicted while the words are
  still worth keeping.
- **The server's own words on failure.** "The transcription server refused the key" is more use than
  "transcription failed", so an error message in the response is preferred to a generic one.

Both `/v1`-suffixed and bare URLs work, because both are what people paste — OpenAI's documentation
gives one and a local server is the other, and doubling the version segment is a 404 that reads as
the feature being broken. There is a test for it.

- 86 tests, up from 80.

### On text-to-speech

This is speech-to-text: sound in, words out. Reading messages *aloud* is a different feature and is
not in this release — say the word and it can be built alongside.

---

## BrightChat v2.20 — audio clips, and somewhere to listen to them

**A sound somebody sends you plays here now, and you can send one.**

### Listening

A non-image attachment used to download and go out as `ACTION_VIEW`, which is right for a PDF and
useless for audio on this phone: there is no audio player installed, so a voice memo ended in "No
app can open this file" after a download you had already waited for. A voice memo is one of the most
ordinary things in a message thread and it was one the app could not open.

Tapping one now opens a player: play and pause, ten seconds back and forward, and a **scrubber** you
drag to move through the recording. It seeks *while* you drag rather than when you let go — seeking
on release is cheaper and it is the wrong feel, because you cannot find a word in a recording you
cannot hear until you have finished looking for it.

`MediaPlayer`, not ExoPlayer — one local file, no streaming, no adaptive bitrate, no DRM. The same
trade the video player made, for the same reasons. The scrubber is a filled bar the height of a
thumb rather than a Material `Slider`, which on this panel is a thin grey line with a small circle
on it, both of which disappear.

### Sending

The send path already streamed video off disk rather than reading it into memory, and audio wants
exactly that — so the distinction it branches on is no longer video-versus-photo but *inline versus
not*. Audio joined video on the streamed side rather than growing a second copy of the same branch.

BrightChat is also a share target for audio now, so **BrightRecorder can send a moment straight
into a conversation** (see BrightRecorder v1.13). A separate `<data>` element rather than a wildcard:
`*/*` would put this app in the chooser for every file on the phone, including the ones it cannot
send.

### One bug that would have been invisible

A share arrives as a URI with a mime type and is cached under a *filename*; the send then reads the
mime back off that filename. Those two mappings have to be inverses of each other and they were not:
a WAV was cached as `.x-wav` — a mime subtype is not a file extension — which the send then labelled
`image/jpeg`, and the other end could not play it. Both directions now come from one table, with a
test that walks every type this app handles out and back again.

- 80 tests, up from 68.

---

## BrightChat v2.18 — photos still uploading no longer lose their bubble

**Send a few photos, scroll up to read while they go, and the ones still uploading
disappeared from the thread — sometimes for good.**

A send puts its bubble up before the server has heard about it: an optimistic row under a
client `tempGuid`, swapped for the real message when the send returns. Everything else that
writes the thread — opening it, the delta sync landing, scrolling back a page — replaces the
whole list with what came out of the local store, and the store only knows about messages the
server has already acknowledged. So a fetch landing mid-upload deleted every optimistic row,
and the send's own reconcile then had no row left to swap into: the photo did not come back
until a later socket event, or until the thread was reopened. Scrolling up while photos upload
is exactly what you do while waiting, and that is what triggers the page fetch.

A fetch now keeps sends that are still in flight, and drops them only once the page actually
contains the real message they were standing in for — recognised by the server echoing our own
`tempGuid` back. Without that second half the same photo would have sat in the thread twice.

**Underneath it was a race, and it explains the rest of the family.** The open thread's message
list was a plain field read and written from two threads at once: the main thread for the live
socket and text sends, a background thread for photo sends and every fetch. Two of those
overlapping silently discarded whichever change lost — a dropped bubble, a photo drawn twice
under two different guids, and (when a chat was opened from a background thread) one
conversation's messages published into another conversation's thread and then cached under its
name. All of it now goes through one place on one thread, so those interleavings cannot happen
rather than being unlikely.

**The crash guard moved to where it cannot be forgotten.** One row per message guid is what
keeps the thread's list from throwing `Key "…" was already used` and taking the app down. That
held only because five separate writers each guaranteed it independently; the sixth would have
reopened the crash silently. It is now enforced at the single point where the thread's messages
are published.

Fixes [light-reports#22] — it closed itself while sending photos.
