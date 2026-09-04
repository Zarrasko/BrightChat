## BrightChat v2.32 — the GIF picker, after using it

Three things that only show up once the thing is in your hand.

**It searched while you were still typing.** There was a debounce, and it was too short to be one:
350 milliseconds is inside the gap between two letters on this keyboard, so a half-typed word
became a search, the grid rearranged itself under your thumb, and every one of those requests spent
part of an hourly allowance on a prefix nobody meant. It now waits nearly a second — long enough
that finishing a word is one request — and the keyboard's **Search** key fires immediately for
anybody who doesn't want to wait it out, putting the keyboard away as it goes, which on this panel
is the difference between seeing two rows of results and seeing none.

**The results were soft, because the picker was asking for the smallest copy of each GIF.** The
panel is 1080 pixels across a hair under four inches, so a cell in a two-column grid is roughly 400
device pixels wide — and the `xs` rendition these services offer is commonly 120 pixels, upscaled
more than three times before it reached your eye. The grid now takes the small-but-not-tiny one,
and steps *up* to medium when a GIF has no such size rather than falling back down to the smallest.
Sending is unchanged and still medium: that file is being uploaded through a phone to a Mac, and an
HD GIF is eight megabytes iMessage re-encodes anyway.

While fixing it: the rendition choice is now an explicit order of preference per job rather than
arithmetic on a size number. The arithmetic looked clever and quietly produced *ties* — two sizes
equally far from the target — and a tie went to whichever the service happened to serialize first,
so which copy of a GIF you were shown was effectively decided by JSON key order.

**And the GIF key sat lower than the + beside it.** The compose row aligns its keys to the bottom,
and GIF was typeset a size smaller than everything else on that row — two different text sizes
bottom-aligned line up their *boxes*, not their baselines, and the smaller one's baseline sits
inside a shorter box. It is the same size as the `+` now, which puts them on one line for real
rather than at one particular font scale.

- 131 tests.

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

**There is nothing to set up.** The APK ships with a search key, so the GIF button works the first
time it is pressed. It arrives from a repository secret rather than a line in this public repo, and
it is scrambled rather than readable in the binary — which stops `strings` and the scrapers that
walk GitHub for things shaped like keys, and, to be straight about it, nothing more than that.

The one thing worth knowing: that allowance is **per key, not per phone**. Every BrightChat draws
on the same one, so a busy hour is busy for everybody, and search says *GIF search is busy — try
shortly, or add your own key in Settings* rather than failing silently. Your own key — free, a
minute on their partner panel — goes in **Settings → GIFs**, typed or scanned off a laptop screen,
and takes precedence from then on.

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

- 129 tests.

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
