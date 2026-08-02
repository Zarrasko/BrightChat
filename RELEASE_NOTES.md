## LightChat v1.9 — v1.8 went home without calling

**v1.8 removed a delay that turned out to be the only thing making the call work.**

`ACTION_CALL` is not a direct line to telecom. It starts an *activity* in the dialer package, and
that activity is what asks telecom to place the call. v1.8 stepped aside the instant the intent
was fired, so the home launch and the call activity's launch reached ActivityManager together —
home won, the call activity was never resumed, and the call it had not placed yet was never
placed. The screen went home and nothing rang.

The 1.8-second wait v1.7 kept "out of caution" was, by accident, exactly what gave that activity
time to exist. v1.8's release notes said the delay bought nothing. That was wrong, and the way it
was wrong is worth writing down: the delay had been introduced for one reason (letting a retry
ladder outlast a slow radio), that reason was correctly removed, and nobody checked whether it
had since acquired a second one.

So the call now goes through `TelecomManager.placeCall` — the same API the dialer's activity
would have called, one step earlier. It hands the number to telecom on the calling thread, before
the function returns, so there is nothing in flight for the home launch to cut off and stepping
aside immediately is safe. Same `CALL_PHONE` permission, no activity in the middle, and the tap
still feels instant.

The `ACTION_CALL` intent is kept as a fallback for a phone with no telecom service or one that
refuses the call — and the delay comes back on that path only, because the reason for it comes
back with it: there is an activity to let start.
