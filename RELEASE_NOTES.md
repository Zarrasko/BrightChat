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
