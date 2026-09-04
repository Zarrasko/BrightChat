## BrightChat v2.31 — GIFs, and a library of your own

**The GIF button that every other messenger has.** Beside the `+` in a thread there is now **GIF**:
trending when it opens, a search box, a two-column grid, and the GIF you pick goes out as a normal
iMessage attachment that plays inline on whatever the other person reads their messages on. The
file is sent, not a link — a link would arrive as a preview card for somebody's CDN, and half the
time as nothing at all.

**The service is KLIPY, because Discord's is.** Google **switched the Tenor API off on 30 June**,
which is what broke the GIF picker in Discord, WhatsApp, X and Bluesky on the same afternoon.
Discord's default GIF search moved to KLIPY; so has WhatsApp's; and KLIPY kept a Tenor-shaped API
so the rest of the internet could follow. This app reads both envelopes, because which one a given
key is served is not something a phone can decide, and the failure mode of guessing is an empty
grid with nothing to explain it.

Search needs a key — theirs, free, a minute on their partner panel — typed into **Settings → GIFs**
or scanned off a laptop screen as a QR code, the same way the transcription key already worked. It
is deliberately not shipped in the app: this repository is public, and a key committed to it is a
key that gets scraped and then rate-limited for everybody.

**Saving keeps the file, not a bookmark.** Hold a GIF in the picker and it is saved — and what that
does is copy the actual GIF onto the phone. The Saved tab then works with no key, no tunnel and no
signal, which matters more than it sounds like: GIF providers lose GIFs, get re-slugged, and — as
this year demonstrated in one go — get switched off. A bookmark to a dead URL is a broken
favourite. A file is a file. There is a **Recent** tab beside it for the ones you have actually
sent, which is metadata only and lives in the cache, because the one you send constantly is worth
offering and is not worth holding disk for forever.

**Two taps to send, one hold to save.** Discord sends on the tap; this doesn't. The photo picker
has had an explicit Send since the day a stray thumb sent somebody a photograph, and a GIF is no
different: the first tap arms the cell, the second sends it — either on Send at the foot, or on
the armed cell itself, so "double-tap to send" is true without a single stray tap sending anything.

**And GIFs now actually move.** Every GIF anybody has ever sent this app arrived as a still, because
`BitmapFactory` hands back the first frame of one and says nothing about it. They animate now —
inline in the thread, in the full-screen viewer, and in the picker's grid — through the platform's
own `ImageDecoder`, which has known how since Android 9. No image library was added to do it: the
whole of it is a decoder call and a hundred-line painter, which is the same bargain the rest of this
app makes with Coil, Glide and Google Play Services.

The picker holds the colour lift for as long as it is open, like the photo picker does, because
choosing a GIF in greyscale is choosing half of one. The wheel scrolls it.

- 125 tests.

## BrightChat v2.30 — one box for a message, not two

BrightControl v3.65 grew a heads-up box of its own. It reads the shade and puts the same box over
the screen for whatever posted — including the notification this app raises a moment before it
draws its own. So with both switched on, a text was one buzz and **two boxes**, one landing on top
of the other.

**This one now stands aside.** BrightControl says who is drawing the box; when it is, the box here
is skipped. The setting is untouched — it still reads as yours, and the row says *On-screen alerts:
BrightControl* rather than pretending to be on while nothing appears. Turn banners off over there
and this app's box comes straight back, with nothing to set here.

**The buzz and the notification never change.** Both happen before the gate, and both have to: the
notification is the record BrightControl reads and LightGlance's dot counts. If BrightControl's
listener grant ever lapses, this app has still buzzed and still filled the shade.

**And it checks BrightControl is really there.** A remembered claim from an app that has since been
uninstalled would have silenced this box for good, with nothing on the phone to explain why.

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
