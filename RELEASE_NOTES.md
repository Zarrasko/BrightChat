## BrightChat v2.35 — an old message no longer resurfaces in the list preview

**A conversation row no longer shows an old message again after newer ones arrived.**

The live socket was folding every `updated-message` into the list row's preview the same way it
folds a brand-new one — but an `updated-message` can be a read receipt or delivery stamp landing
on a message from hours or days ago. The row took that older message's text and date and rewound
itself to it, so the list showed a message the user had long since moved past ("cleaners are
done", again) until the next refresh re-sorted it. The socket path now applies the same
out-of-order guard the sweep path always had — an event older than the row's newest activity
leaves the preview alone — so a late receipt can no longer walk the row backwards.

Fixes [light-reports#292] — the home screen showed an old message again.

- 133 tests.
