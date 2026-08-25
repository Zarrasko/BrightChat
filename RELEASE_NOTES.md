## BrightChat v2.29 — who you talked to, and one branch that could ship July

**Days can ask who you talked to.** LightNotebook's journal already knows where you were; it can now
ask this app who you spoke to on a given day. It is a query, not a log: every message this phone has
synced is in the local table with its date, so the answer is a `GROUP BY` — nothing to record as it
happens, nothing missed because the app was closed, and retroactive across everything ever synced in
a way a recorder could not be.

Names only. **No message text ever leaves this app**, and nothing in the provider says what was
said. What a day gets is the first and last time you exchanged something, the conversation's name,
whether it was a group, how many messages, and whether they replied at all — that last one because
talking *at* someone and talking *with* them are different days. The name is the same iMessage-style
title the app shows itself, so a day never calls someone by a phone number the rest of the app calls
Alex.

This existed on `main` and had never reached `develop`, which is the odd part and the reason for the
rest of this release.

**`main` could have shipped July's app to everyone.** The release workflow triggered on pushes to
`develop` *and* `main`, and `main` had been 66 commits behind develop since the end of July. Any
push to it — a stray click, a merge from a branch someone thought was current — would have built,
signed and published an APK from that old commit, and BrightMarket would have offered it to every
install as an update, on top of a version far newer than it. Nothing about it would have looked
wrong: the build would go green and the release would appear.

`main` is the same commit as develop now, and it is no longer a release trigger. `develop` is the
only branch that publishes.

**A change into develop gets compiled before it ships, not by shipping.** develop is both the
default branch and the release trigger, and the Check workflow ignored it — so the push that
released a change was also the first thing to compile it. Check now runs on pull requests, so a
pull request into develop is built before it can become a release. It is deliberately not added to
the push trigger; that would build every release twice.

Also: light-common moves to 1.2.3, and every action in both workflows is pinned to a commit SHA
rather than a moving version tag.

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
