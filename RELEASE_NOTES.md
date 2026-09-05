## BrightChat v2.34 — a number saved twice no longer crashes the new-message list

**Starting a message to a number saved under two contacts no longer closes the app.**

Typing into New Message searched the address book and offered one row per number — but a number
that lives under two contacts (a duplicate contact, or a line you share with somebody else) came
out twice, both rows carrying the same address. The picker keys its list on that address, and a
list with two rows on the same key throws instead of drawing, so the app closed the moment the
matches rendered. Rows are now de-duplicated by the number itself — the same last-ten-digits
identity the rest of the app compares addresses with — so a shared line is offered once, under
the first name, while a person's mobile and work line stay two rows exactly as before.

Fixes [light-reports#291] — the app closed while entering a number to send a new message.

- 133 tests.
