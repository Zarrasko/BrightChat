## LightChat v1.8 — stepping aside happens on the tap

**The 1.8-second wait is gone.** Press Call and LightChat leaves immediately, so the call screen
is what you are looking at rather than a chat thread you have finished with.

The delay was inherited from a problem that no longer exists. It was there to walk out past a
slow radio, back when the plan was to *ask* the dialer to show a call — a request about a call
telecom has not registered yet is dropped in silence, so v1.5's ladder of three attempts at 250,
700 and 1500ms was real work. v1.7 replaced all of that with going home, and kept the timing out
of caution rather than for a reason. Leaving does not depend on the call existing: the intent
that places it has already been issued, and telecom brings the in-call UI up when it is ready
whether or not this app is still in front. All the wait bought was 1.8 seconds of looking at the
wrong screen.

`showInCallScreen` is still asked for, once, immediately before leaving. It costs a single call
on a dialer that ignores it, and on any other phone it is the correct route — this app runs on
more phones than one, and "the LightOS dialer is unhelpful so always go home" would be wrong on
all of them.

Everything else about the step aside is unchanged: `ACTION_MAIN` + `CATEGORY_HOME` rather than
`finish()`, so the app is backgrounded and comes back with the thread exactly where it was.
