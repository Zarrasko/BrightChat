## LightChat v1.7 — get out of the way, don't push in front

**The problem was never the dialer. It was this app.** LightChat places the call and then stays
in front of it, because it is an ordinary activity and nothing has asked it to leave.

Two versions were spent shoving something over the top of it. v1.5 asked
`TelecomManager.showInCallScreen` to pull the call screen forward, three times. v1.6 launched the
phone app outright to the same end. Both treat "LightChat is still on screen" as a thing to cover
up rather than as the thing that is wrong.

So it now simply goes home, 1.8 seconds after the call is placed — `ACTION_MAIN` +
`CATEGORY_HOME`, exactly what the home key does. The call screen is what LightOS shows when
nothing else is in the way, so nothing needs to be launched or asked for.

It also leaves the phone somewhere sensible afterwards. Launching the dialer left a task stack
with LightChat underneath it, so hanging up dropped you back into a conversation you had already
finished with. Going home means the call ends where a call ending should.

**Backgrounded, not closed.** Going home rather than calling `finish()` on the activity: this
runs from a posted runnable with no activity to hand, and finishing would throw away the thread's
scroll position for the sake of a call. LightChat comes back exactly as it was.

The three `showInCallScreen` attempts stay ahead of it, unchanged. On a dialer that honours them
the call screen is already up by the time this fires, and going home to a foreground call screen
does nothing at all.
