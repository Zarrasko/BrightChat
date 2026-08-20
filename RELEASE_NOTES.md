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
