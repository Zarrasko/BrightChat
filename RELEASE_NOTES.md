## BrightChat v2.28 — delete a conversation from the top of it

**"Add a 'clear' option to delete the chat when you tap the phone number at the top of a chat."**
LightOS's own messenger does exactly that: press the contact name in the header and you get the
verbs for managing that thread, one of which throws it away. BrightChat's header already opened
something — the contact page, with the people in the thread, the note, the photos and the links —
but the only way to be rid of a conversation was to go back to the list and swipe the row. The
delete existed; it just wasn't where you were looking when you wanted it.

It is now the last row on the contact page, under the photos and the links, and it confirms the way
every other destructive tap on that page confirms: the label changes to **Delete this conversation?**
and a second tap does it. Any other tap on the page disarms it, which is the same guard the
per-member Remove and the Leave row already share — a stray touch cannot delete a thread. Deleting
clears the open conversation, so the contact page and the thread underneath it both unmount and you
land back on the list with the row already gone.

Two decisions worth writing down. It is offered on **one-to-one chats as well as groups**: the other
management verbs are group verbs (rename, add, remove, leave) and are hidden in a two-person chat,
but a two-person chat is the one you most often want gone. And there is deliberately **no
local-only "clear"** to sit beside it. The store on the phone is a cache of Messages on the Mac, so
emptying it here would refill on the next sync a few seconds later, which reads as the delete having
silently failed. The row calls the server's `DELETE /api/v1/chat/:guid` — the same path the swipe
always used — and the server gates that on the Private API, so the row only appears when the Private
API is live rather than appearing and then apologizing.

Fixes [light-reports#36] — no way to delete a chat from inside the chat.

- 96 tests.
