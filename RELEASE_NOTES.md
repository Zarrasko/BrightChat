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
