## BrightChat v2.14 — frame the crop, peek at the times

**Backgrounds: drag the Fill crop.** In the background editor's Fill mode the preview *is*
the crop, so it now drags: slide the photo through its overflow and pick the exact spot the
screen keeps. The framing is saved with the recipe. Also fixed: swapping the photo under an
existing filter stack actually swaps it — the render cache was keyed on the recipe alone, so
a replaced photo kept serving the old image.

**Slide the thread for times.** Timestamps aren't drawn in a thread — a column of them is
exactly the clutter this app exists to not have — but "when did this arrive" is a fair
question. So, iMessage's answer: drag the thread left and every visible message's time
slides in from the right edge (delivery time for your own turns once the server has
reported one, arrival for the rest); let go and it springs back. The reveal is read only at
draw time, so the drag moves every row without recomposing any of them.

## BrightChat v2.13 — a failed agent reply no longer crashes the next send

**Texting an agent that never answered could close the app on your next message.**

When you send a turn to an agent, the thread shows your message immediately — an
optimistic copy, stamped with placeholder id 0 — while the real row goes into the
local store. If the agent replied, the thread reloaded from the store and every row
got its real database id. But if the agent *didn't* reply — endpoint down, bad key,
timeout — the error path kept the optimistic list as it was, id 0 and all. Send
again and a second id-0 row joined the first, and Compose's list refused to draw
two rows with the same key: the app died on the spot with
`Key "0" was already used`.

The store's insert has always returned the new row's id; the optimistic copy just
never used it. Now it does — the message is persisted first and the thread shows it
under its real id, so no two turns can ever share a key, however many replies fail
in a row.

Fixes [light-reports#19] — texting an agent that never replied closed the app.
