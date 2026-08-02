## LightChat v1.3 — you can call the person you're texting

**A conversation is with somebody, and sometimes the thing to do is ring them.** There is now a
Call in the thread header and one beside every name on the contact page.

The app doesn't place the call. It hands the number to whatever holds the dialer —
`ACTION_DIAL`, which opens the calling app with the number filled in and waits for the green
button. The other option, `ACTION_CALL`, rings straight from the tap and needs `CALL_PHONE`, a
permission this app has never held and has no other use for. It would also turn a misplaced
thumb on the contact page into a call to somebody, with no undo, on a page whose other verbs
are Remove and Leave — and which is scrolled with the wheel precisely so a thumb isn't sitting
over the taps.

**Two places, because they answer different questions.** The thread header offers Call only for
a one-to-one: a group has no default person to ring, and picking one on your behalf is the one
thing that screen cannot honestly do. The contact page lists every member, so there the choice
is just a tap — which is where a group's Call belongs and why the row has one at all.

**An iMessage handle is as often an Apple ID as it is a number**, and the two are not
distinguishable by asking Android; they arrive from the server as opaque strings. A handle with
an `@` in it is never callable, and neither is one with letters in it — `1-800-FLOWERS` reaches
no keypad. Those rows simply don't offer a Call rather than offering a dimmed one, because a
verb that explains itself only after being tapped is a verb that shouldn't be there. Short codes
do get one: the number that texted you a verification code is one worth being able to look at,
and DIAL only ever fills the keypad in.

Two smaller things that would each have been a bug report. The number becomes a `tel:` URI
through `Uri.fromParts` rather than `Uri.parse("tel:$number")` — a `#` in a stored extension
terminates a parsed URI at the fragment, so the dialer would open on half a number. And the
manifest gains a `<queries>` entry for `ACTION_DIAL`: without it, Android 11 package visibility
hides the dialer from `queryIntentActivities`, the availability check comes back empty on a
phone that plainly has a phone app, and the verb hides itself. That is the same failure the
LightNotebook entry beside it exists to prevent, and the one that cost a day when Roll reported
LightChat couldn't receive photos.

`Dialer.callable` is pure Kotlin with five unit tests over the handle shapes BlueBubbles
actually returns.
