## BrightChat v2.16 — sending a batch of photos no longer closes the app

**Send several photos at once and the app could die the moment they landed.**

A send puts its bubble up before the server has heard about it: an optimistic row keyed by a
client `tempGuid`, so the photo is in the thread while it uploads. The real message then
arrives three separate ways — the HTTP send returns it, the socket announces it as a new
message, and the socket updates it again for each delivery and read stamp — and the thread
folds all of those onto the one row by matching either the real guid or the tempGuid the
optimistic row is still keyed by.

Photos are big and the upload blocks, so with several queued there was a long window where
the socket's announcement arrived *before* the send returned. The announcement carried the
real guid but nothing to match the optimistic row on, so it was appended as its own row: now
the optimistic row and the real row were both in the thread. The next update for that message
matched the optimistic row by tempGuid — it comes first in the list — and wrote the real
message over it. Two rows, one guid. Compose will not draw a list with a repeated key, so the
thread threw `Key "…" was already used` on the next frame and the app closed itself.

The merge now writes the matched row *and* drops any other row already carrying that guid, so
one message can only ever occupy one row however its three answers are ordered. That rule is
now the point of the function rather than a property of the order things happened to arrive
in, and it is unit-tested against the interleavings a batch of photos produces.

Fixes [light-reports#21] — sending many photos closed the app.
