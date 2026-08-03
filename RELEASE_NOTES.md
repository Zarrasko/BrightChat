## LightChat v2.8 — Notifications say who

**A group notification never told you who texted, and a tapback told you nothing at all. Both now
name the person responsible.**

### A group message names its sender

In a group the notification title is the room — "Poker Night" — so there was nowhere for the
sender's name to go, and the body was the bare message. A text from a five-person thread read as an
anonymous line of words. The body is now `Alex: on my way`, the same shape iMessage uses.

A 1:1 message is left alone: the title is already the person, so prefixing the body would only
repeat it. An *unnamed* group is titled by its members ("Alex, Liz") and still names the sender, because
the alternative is a three-way group looking exactly like a 1:1.

### A tapback says who reacted, and to what

A tapback arrives as a message whose own text is empty — the reaction is in
`associatedMessageType`, and what it points at is a guid in `associatedMessageGuid` — so what got
posted was a title over a blank line. Now it reads `Alex loved “see you at 6”`, which means looking
the target message up in the local store (`messageByGuid`, deliberately not scoped to one chat: a
forked group spans sibling rooms and a reaction can land in a different one than its target). When
the target isn't held locally the line says "a message", which is at least honest.

Two smaller consequences of getting there:

- **Someone *removing* a tapback no longer buzzes.** It still bumps the thread in the list, which is
  iMessage's behaviour, but there was never anything to say about it and it used to post an empty
  notification.
- **A reaction to something *you* said now survives the background poll.** The poll judged a
  conversation row by `lastFromMe`, which describes the newest real *speech* — so a tapback on your
  own message failed the test and was dropped. That is precisely the case worth being told about.

### One place that decides the words

There are three routes to an alert — the live socket, the background catch-up poll, and the
deferred flush when the app stops — and each phrased things itself. Where they agreed they agreed by
accident. The phrasing now lives in `AlertText`, so the same message reads the same whether the
phone was awake when it arrived or asleep. `Conversation` gained a `lastSender` so the poll, which
works from the list rather than from messages, has a name to use at all; rows cached by an older
build have no sender until the next sweep rewrites them, and stay unprefixed rather than guessing.

## LightChat v2.7 — The shake asks instead of interrupting

**Two changes, one of them invisible: shaking the phone no longer throws a report sheet over what
you were doing, and the reporting code behind it is now a shared library rather than a copy kept
in this app.**

### The shake offers a chip, not a sheet

The first version got the shape of the question wrong. A shake is a gesture the phone can
misread — and the cost of misreading it was paid every single time, because a full-screen sheet
landed on top of whatever you were looking at to ask about a problem that may not have existed. On
a 3.92" panel that is a bad trade against a report that might not be real.

So the offer is small, it sits out of the way, and **silence is an answer**. A shake puts a
"SEND ERROR?" chip in the bottom corner; ignore it for four seconds and it fades. Nothing is lost
by ignoring it: an unsent crash log stays on disk and is offered again on the next launch, and a
failure the app noticed itself will not ask again for an hour. Only a tap opens the sheet.

A crash offer stands for eight seconds rather than four. It is the one offer that cannot be
reconstructed from nothing if you miss it.

The chip is drawn in its own window rather than placed in the layout, so it lands in the same
corner in every app regardless of how that app is built, and it cannot swallow a tap meant for
what is underneath it.

Issue titles now follow the same convention as every other app — `LightChat v2.7.x — <headline>`,
labelled `chat` — instead of the `chat: <headline>` this app had invented.

### Reporting is a library now

The eight files under `com.gios.lightchat.report` are gone. They are
`com.gios:light-common:1.0.1`, resolved from GitHub Packages and shared with every other app that
was keeping its own copy of the same code.

Nothing about this is visible on the phone. It matters because a fix to the reporter used to mean
editing it in ten places and getting eight of them subtly wrong — which is exactly how the
sheet-instead-of-chip mistake reached ten apps before anyone saw it once.

One thing had to change shape. `BuildConfig` does not cross a library boundary, so the app hands
its name, its triage label and its report key to `LightReport.install()` at startup rather than the
reporter reading them out of the build. Skip that call and reporting is simply inert, which is a
better failure than a reporter filing issues with a blank app name.

Same note field, same queue-to-disk-first behaviour, same gesture tuning.
