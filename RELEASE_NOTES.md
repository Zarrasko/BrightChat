## LightChat v2.3 — a contact saved on the phone actually names somebody

**Save Kate Jacobs on the handset and the app kept showing her number.** Everywhere: the
conversation list, the thread header, the notification that woke you up.

The cause is one this app has had since it was written and only became visible now that it can
save contacts. Names come from `Contacts`, an index built from the **BlueBubbles server's** copy
of the Mac's address book. The handset's own address book was never read for this — v2.2 taught
the *dialer* to fold the two together, but that merge ran in one direction and only the dialer
used it. Nothing else in the app had any way to learn a name you had just typed.

The index is now built from both, and **the phone's wins a collision**, because it is the one you
just edited. Where the Mac has a name and the phone does not, the Mac's stands.

It happens in two places, for two different waits. On the server fetch, so the merge is what gets
persisted — which is what lets `SocketService` name a notification sender while the app is not
running. And once when the ViewModel is constructed, because the persisted index is the *last*
merge: somebody saved since then would otherwise be missing until the next sync, which on a phone
that has been open all day is a long time to keep showing digits for a person you have just named.

Every number of every contact goes into the index, not just the first. A person is reached on
whichever line they happened to message from, and an index that only knows their mobile names
half their conversations.

Two things deliberately kept out. A contact with no name is titled by its own number for the
dialer's list; writing that back would put digits over a real name from the server. And so are
the rows v2.2 synthesises from the message index itself, which would otherwise round-trip a name
back into the index it came from.

All of this needs the contacts permission, which is asked for the first time you open the Dial
tab. Without it every path here is an empty map and the behaviour is exactly what it was.
