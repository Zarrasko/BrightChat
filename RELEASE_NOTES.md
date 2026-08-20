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
