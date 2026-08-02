## LightChat v1.4 — calling actually places the call

**v1.3's Call opened the dialer on an empty keypad.** The number was there in the intent and
never arrived on screen, which looks exactly like the intent being ignored and is the reason
nothing anywhere reported an error.

The cause was `Uri.fromParts("tel", number, null)`. Its scheme-specific part is documented as
*decoded*, so it percent-encodes on the way out and an E.164 handle leaves as
`tel:%2B13152122695`. A dialer that decodes that is fine. LightOS's is the only dialer on the
phone and does not, so it opened with nothing filled in. The v1.3 notes explained at some length
why `fromParts` was the careful choice over `Uri.parse` — the reasoning was about a `#` being
read as a fragment, which is real, and it traded a rare bug for one that happens every time.

The number now goes through `Dialer.dialable` first, which reduces it to the characters that
change what gets dialled — digits, a leading `+`, `*` and `#`, and the `,` and `;` that make a
stored extension work — and drops the brackets, spaces and dashes that are only there for a
human to read. Nothing left needs encoding except `#`, which is escaped by hand. Four unit tests
pin it, including the exact string that failed.

**And the call is now placed rather than typed.** `ACTION_CALL` hands the number to the telecom
stack, which parses nothing and does not care what the dialer app makes of a URI — on this phone
that is the difference between a call and a keypad. It needs `CALL_PHONE`, the first dangerous
permission this app has ever held, so:

- It is asked for on the **first tap of Call**, never at launch. A permission dialog that
  appears because you opened a conversation is one that gets refused.
- Refusing is not a dead end. The call falls back to `ACTION_DIAL`, the same route as v1.3 but
  now with a URI that works. Refuse twice and Android stops showing the dialog entirely, which
  lands in that same fallback rather than leaving an inert button.
- The permission is re-checked at the moment of the call, not trusted from when the screen was
  composed — it can be revoked from settings in between, and `ACTION_CALL` without it is a
  `SecurityException` that takes the process down rather than an error anyone sees.

One more thing that was wrong and would have hidden the feature outright: whether to show Call
at all was decided by asking whether an activity handles `ACTION_DIAL`, copied from how the note
row checks for LightNotebook. That is the wrong question twice over. `ACTION_CALL` doesn't go to
an activity at all, so a phone that calls perfectly well can resolve nothing; and with one dialer
installed, that app's intent filters alone decide whether the verb appears. It now asks
`FEATURE_TELEPHONY` — can this hardware make a call — and keeps the `ACTION_DIAL` lookup only as
a second chance.
