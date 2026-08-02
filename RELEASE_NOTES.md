## LightChat v2.2 — both address books, and a way off a speed dial

### Everybody you message has a contact now

Two address books, and neither is complete on its own. The phone's has landlines and people who
have never texted. The BlueBubbles index is the Mac's contacts as iMessage sees them, and it
holds people who exist on the account but were never saved to this handset — a number you have
messaged for a year from the Mac was a stranger to the dialer, showing up as digits with no name
or not at all.

The dialer's list is now both, folded together. **The phone wins every collision**, and that is
the whole of the merge rule: its rows carry structure — several numbers, a label per number, your
own choice of default — while the message index is one name per key with none of that. Where both
know a number, keeping the phone's row loses nothing; the other way round would throw away
everything but the name.

Somebody who exists only in the message index gets a row reading **"From messages"** under their
name, rather than a silent one. The difference matters when you are looking at it: that number has
no entry on the phone, so the Save verb on their contact page is the thing to do about it, and a
blank label would just look like a contact missing its type.

Email handles are dropped. This list exists to be dialled from, and a row whose only verb cannot
work is a row that lies about what it does — the same reason the address-book query reads numbers
and not emails.

The ids for those synthesised rows are negative and derived from the number rather than counted.
They share a list with real contact ids and are used as list keys, so they have to be unique
against those and stable across a reload; a counter would renumber everybody the moment one
contact was added.

### The speed dials are visible, and can be cleared

v2.0 let you put somebody on a key and gave you no way to see who was there or take them off. Both
were the same omission: a slot you cannot see is a slot you cannot change your mind about.

With nothing typed, the list is now the speed dials — the digit, the name, the number, and
**Clear**. Tapping the name rings them, which makes the resting screen useful rather than the
blank it was in v2.1. Typing a digit replaces it with search, exactly as before.

Clear as a word rather than a long-press or a swipe, deliberately. Holding the key already means
"ring this", and giving that gesture a second meaning depending on where the finger happens to be
would make the one gesture on this screen ambiguous. Assigning now says so too: "Alex on 3 — hold
3 to ring, or Clear it below".

Four more unit tests over the merge: the phone winning a collision, emails being dropped, the ids
being negative, distinct and stable, and an empty index changing nothing.
