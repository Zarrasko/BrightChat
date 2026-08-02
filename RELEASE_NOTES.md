## LightChat v2.0 — a dialer, a contacts list, and Favorites as the front page

**Four things, and they add up to not needing the phone app.** Calling has worked since v1.9;
what was missing was somewhere to call *from* that wasn't already a conversation.

### The Dial tab is the contacts list

One screen, not two. The pad asks "ring this number" and "find this person" at the same time and
the digits answer both: `442` narrows to everyone whose name starts a word with G-I-A **and**
everyone whose number contains 442, in that order. Nothing has to be told which you meant, and
with nothing typed it is simply the address book — which is why there is no separate contacts
screen to switch to.

T9 matches **at the start of a word, never in the middle**, the same rule the message picker's
letter search follows and for the same reason: an infix match returns half the address book for
two digits, and the whole value of typing is that the list gets shorter. "Lupo" answers to `58`
and not to `87`. Numbers match anywhere, because a number is searched by the part you remember,
which is the tail far more often than the country code. Name matches always sort above number
matches — typing `442` because you mean Gia and getting four people whose numbers happen to
contain it above her is the search failing at its only job.

**Press and hold 1–9 to speed dial.** An empty slot takes whoever is at the top of the list, so
assigning is the same gesture as using, one step earlier: search for somebody, hold a free key,
and from then on that key rings them from an empty pad. No assignment screen and no picker; both
would be more interface than the feature is worth. Holding 0 types a `+`, which is older than
speed dial and the only way to reach E.164 on a pad with no plus key.

The letters are drawn under the digits because they are the only thing that explains why typing
`442` found Gia — without them T9 is a coincidence rather than a feature. A number long enough to
be real gets its own **Call** row above the results, since a number nobody has saved is exactly
what a contacts list cannot help with.

Reading the address book needs `READ_CONTACTS`, asked for the first time you open the tab rather
than at launch. It is read on the phone and nothing is sent anywhere. The BlueBubbles index this
app already had is the Mac's contacts and only knows people who have iMessaged you — no use for
ringing a landline.

### Favorites is the front page

The app opens on the starred list. It is the handful of people this phone is actually for, and
opening on the full message list meant scrolling past everyone else to reach them. Messages is
one tap away and still called Messages.

### Pinned chats

**A pin is an ordering, not a second star.** Starring already answers "does this belong on the
front page"; pinning answers "and in what order", which is only a meaningful question about
things already there. So only a starred chat can be pinned, pinned ones lead the starred list in
the order you pinned them, and unstarring drops the pin with it — otherwise the app would hold a
position for a list the chat is no longer in, and re-starring months later would resurrect an
order nobody chose.

Pin order is the order you pinned in, newest first, not the order of the list underneath. The
list underneath moves every time somebody messages you, and the whole reason to pin something is
wanting it somewhere that does not move. Capped at five, because a pinned list long enough to
scroll is the starred list again with an extra rule attached.

Its own small verb on the row rather than another gesture: the row already spends its long-press
on starring and its swipe on Delete, and a third would be one too many to remember. Re-pinning
something already pinned moves it to the front, which is how you reorder — there is no drag on
this panel and there should not be one.

Twenty-three unit tests over the T9 mapping, word-start matching, the address-book merge, search
ordering and the pin rules.
