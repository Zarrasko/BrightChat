## LightChat v2.4 — starting a message to somebody you just saved

**The New Message picker could not find a contact saved on the phone.** Typing their name
returned nothing and the only way through was typing the number out in full — which is a picker
failing at the one thing it is for.

Same cause as v2.3, in a second place. `contactList` was built from the BlueBubbles server's
contacts, which is the Mac's address book; the handset's own was never read for it. v2.3 fixed
the naming half — the conversation list, the thread header, notifications — and this is the
picking half.

The address book is merged in on every refresh rather than where `contactList` is first built,
because that is inside the once-a-session guard: the server's contact list is a slow call worth
making once, and the phone's book is a local query and the half that changes while the app is
open. You save somebody and come straight back.

One row per **number**, not per person, because the picker adds a recipient by address and has to
be told which line — a person with a mobile and a work number is two rows, exactly as the
server's list already represents them. De-duplicated against the server's rows by the same
normalised key the rest of the app compares addresses with, and the phone's row wins: a number
saved in both should be offered under the name you gave it here.

Rows with no real name are skipped. `merge` titles a nameless contact by its own number, so
including them would mean a row whose name and address are the same digits — nothing over typing
it, and the "Add …" row already covers that.

Needs the contacts permission, asked for the first time you open the Dial tab. Without it this is
the old behaviour exactly.
