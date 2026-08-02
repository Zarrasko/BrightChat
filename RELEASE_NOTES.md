## LightChat v1.5 — the call screen comes up, and Call asks first

**v1.4 placed the call and showed nothing.** The call connected, in the background, with the
chat thread still on screen and no visible way to hang it up. That is worse than not calling at
all: the call is real, it is billing, and the only sign of it is a notification.

`ACTION_CALL` hands the number to telecom and stops there. Putting the call screen up is the
default dialer's `InCallService`, which launches its own activity when telecom tells it a call
exists — and LightOS's dialer does not do that for a call another app started. So the app now
asks for it: `TelecomManager.showInCallScreen`, which is the documented route, needs no
permission of its own, and does nothing when there is no ongoing call.

It asks three times, at 250ms, 700ms and 1.5s. `startActivity` returns before telecom has a call
to show, so asking immediately is asking about a call that does not exist yet and the request is
dropped in silence — that single fact is why one attempt would have looked like no fix at all.
The later attempts land on a screen that is already up, where re-showing it does nothing.
Cheaper than watching call state, which needs `READ_PHONE_STATE` and a listener to unregister.

### Call asks before it rings

Since v1.4 the tap rings immediately, and the thread header sits directly above a scrolling list
— a thumb that overshoots the top of the thread lands on it. Call is now armed by the first tap
and placed by the second: "Call" becomes "Call?", brightening the way "Remove?" does, and a
second tap rings. Same pattern as Remove on the contact page, and the same reason.

One difference from Remove, which needed no timeout because it lives in a list and is disarmed
by tapping anything else in it. The header has nothing beside it to tap, so an armed "Call?"
would wait indefinitely and turn the next stray touch into a call. It disarms itself after four
seconds. On the contact page, arming one person's Call disarms every other, so two rows can
never both be asking — and Call and Remove hold separate state, or arming one would arm the
other in a row where they sit side by side.
