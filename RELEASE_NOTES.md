## LightChat v2.1 — five things, mostly getting out of the way

### Opening a chat no longer opens the keyboard

Reading a thread is the common case and typing is the deliberate one; a keyboard covering half
the messages you came to read had that backwards. Two halves to the fix, because there are two
ways it happened. The activity now declares `stateAlwaysHidden`, which covers the window
appearing. And the thread clears focus when it opens, which covers everything else — the compose
field is the only focusable thing on the screen, so anything that hands focus around (coming back
from the dialer, a notification deep link swapping the open thread under a composed screen) lands
on it, and focus on a text field is what summons the IME.

### Shake three times to report a glitch

A chip appears in the corner; tap it and LightChat files a GitHub issue against the private
tracker with what happened, an optional note, the build and firmware, free space and heap, and —
only while the row stays ticked — a screenshot taken at the moment of the shake.

Ported from Roll unchanged in behaviour, deliberately: this is diagnostic UI, not product
surface, so it should be one learned gesture across every app on the phone rather than four
slightly different ones. Only the drawing is rewritten, because LightChat has its own type scale
and none of Roll's `LightThemeTokens`.

**Nothing opens by itself.** A shake is a gesture a phone can misread, so the shake only ever
offers, in a corner, for four seconds. Ignoring it is "not now" rather than "no" — an unsent
crash log stays on disk for the next launch to ask about again. The screenshot is taken at the
moment of the gesture rather than when the sheet opens: by then the thing that looked wrong may
have redrawn itself, and a report about a glitch whose picture shows the app working is worse
than one with no picture at all.

The key it posts with only files issues on one private repo — it cannot read that repo or touch
any other, which is why the screenshot travels as base64 in the issue body rather than as a
committed file. A build without the secret still works; reports queue on the phone and go out
from a later build that has it.

### Save an unknown sender to contacts

A **Save** verb on the contact page, on any member the address book cannot name. It hands the
number to the contacts app with `ACTION_INSERT_OR_EDIT` and waits — this app does not write
contacts and should not, since the alternative is `WRITE_CONTACTS` and reimplementing a form that
already exists.

`INSERT_OR_EDIT` rather than `INSERT`, which is one constant and the difference between a clean
address book and two Alexes: a number you have been texted by is often somebody already saved
under a different line, and `INSERT` would duplicate them without asking.

### The dialer lists nobody until you type

And the "Dial" title is gone. The address book in full is a list nobody scrolls to find somebody
in — that is what the pad is for — and having it on screen at rest meant every glance at the tab
started with the wrong three hundred people. One digit is enough to make the list worth having.
The title went for the same reason: on a 3.92" panel a header that only labels is a row of
contacts given up, and the keypad says what the screen is more plainly than the word did.

### Pinning moved to the row's long-press

The text verb in the row shared its line with the title and the timestamp, so the title lost
about a third of its width to a word only relevant on one tab — and a tap target that small,
inside a row that is itself clickable and swipeable, is a coin toss.

Long-press now means the useful verb for the list you are on: star elsewhere, pin on Favorites,
where the chat is already starred and starring is the one thing it cannot do. A pinned row wears
a `↑` prefix on its title, the same "something is true about this row" language as the `•` unread
marker. Unstarring moved into the swipe reveal, taking Delete's place on that tab — it is the
destructive verb and that is where the destructive verb goes.
