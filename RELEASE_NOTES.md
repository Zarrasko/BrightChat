## BrightChat v2.17 — Newsletter: one message, many chats

**A new destination under New Message: Newsletter.**

You build named *batches* — a batch is a list of recipients, mixed freely from your group chats
and from your contacts — and then write one message, photos and all, to a batch. It goes out to
every recipient in it.

**Separately, one thread each, and that is the whole feature.** This is not a group chat. Each
recipient receives their own message in their own conversation, sees nobody else on the list,
and replies to you alone. A group chat would have been one API call and a completely different
thing to have built.

**Groups and people are stored differently on purpose.** A group can only be addressed by the
guid of a room that already exists on the server, because a group has no handle; a person is
addressed by handle, whose 1:1 guid can be constructed, which is what lets a batch include
somebody this phone has never messaged. Keeping the two apart is what stops a group in a batch
from quietly delivering to one of its members instead of the room.

**Sending is sequential, with a pause between recipients.** Not politeness — the AppleScript
path drives Messages.app on the Mac through an Apple Event, and firing forty of those back to
back is how that path starts dropping messages while still reporting them sent. A broadcast to
forty costs about twelve extra seconds; a recipient who silently never hears from you costs
more. Text goes first and the photos follow, so that a connection dying halfway through a
recipient still lands the words.

**One unreachable number does not stop the batch.** Failures are collected per recipient rather
than aborting the run, and the outcome line names who did not get it — so "who missed this" is
answerable without opening thirty-nine threads.

**Send asks once.** The composer lists every recipient rather than counting them in a header,
and the first tap on Send arms it while the second sends; editing the message or the photos
disarms it again. A broadcast has no undo, and it can go to the wrong forty people exactly as
easily as the right ones.
