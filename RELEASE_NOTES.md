## LightChat v1.6 — the phone app is opened, not asked

**v1.5 asked LightOS's dialer to show the call. It didn't.**

`TelecomManager.showInCallScreen` is a *request*: it tells the default dialer to put its in-call
activity up, and a dialer that doesn't act on it leaves the call running behind whatever you
were doing with nothing to say so. Retrying it three times past the radio, which is what v1.5
added, only makes the same request three times. The premise was wrong, not the timing.

So the phone app is now opened outright, 1.8 seconds after the call is placed — its launcher
intent, exactly what happens when you tap Phone on the home screen, which is the one route that
has always worked. With a call up, that is the screen it lands on.

**`FLAG_ACTIVITY_NEW_TASK` and nothing else**, which is the part worth getting right. That
combination brings the phone app's *existing* task to the front, so if the in-call activity is
already running — because the dialer put it up by itself, or because `showInCallScreen` did work
on some other build — this surfaces exactly that and changes nothing. Adding `CLEAR_TOP` or
`SINGLE_TOP` would tear the in-call activity down and leave the keypad in front of a live call:
the same failure this sequence exists to prevent, reached from the other side.

The three `showInCallScreen` attempts are kept ahead of it. They cost nothing on a dialer that
ignores them, and on any other phone they are the correct route — this app is public, and
hardcoding "LightOS's dialer is broken so always kick the app" would be wrong everywhere else.
For the same reason the package comes from `defaultDialerPackage` rather than a literal: it is
the same answer the system used to route the call, and a hardcoded package name is a bug on
every phone but one.
