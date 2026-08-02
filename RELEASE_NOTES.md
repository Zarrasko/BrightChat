## LightChat v1.2 — Roll can send to a group, and a login code finds you

**Two things this app knew and nothing else could reach: which groups you're in, and the
six digits that just arrived.** Both are now offered to the apps that need them.

### Sending a photograph to a group

Roll's send picker asks *who*, and it answers out of the phone's address book. An address
book knows about people. A group iMessage is not a person — it is a chat room living on the
Mac, identified by a guid like `iMessage;+;chat684…`, and the only copy of that fact on the
phone is this app's conversation list. So "send this to the group" had no route at all: the
best Roll could manage was handing the photographs over unaddressed and leaving you to find
the thread by hand.

There is now a read-only `ChatsProvider` serving the group conversations — guid, title,
member count, last activity — with the titles already resolved through the BlueBubbles
address book, so a group reads the same in Roll's picker as it does in the conversation
list. Groups only: a one-to-one thread's guid *is* its handle, which Roll already has from
the address book, so serving the rest would expose the shape of every conversation on the
phone in exchange for nothing.

Receiving changed to match. A share can now carry a `chat_guid` extra alongside the AOSP
`address` one, and when it does the guid is used **verbatim**. That distinction is the whole
fix rather than a detail: a person's chat guid can be *constructed* from their handle, which
is what makes sending to a thread that doesn't exist yet work, but constructing one for a
group produces `iMessage;-;<somebody>` — by definition a two-person chat. The photograph
would have gone to one member instead of the room, silently and with no error. The thread
also opens from the stored conversation now, so a group lands on its real name and members
rather than on a nameless placeholder titled "Unknown".

### A login code always gets through, and the keyboard can offer it

A verification code arriving from a number you have never texted was being silenced, and
correctly so by the letter of the rule: unknown senders don't interrupt unless you've asked
them to. But that setting was quietly breaking logging in — nothing appeared to fail, and
the cause was a preference set weeks earlier. A message carrying a one-time code now always
alerts, stranger or not. It is the one exception, and it earns it: every other unknown
sender is an interruption you didn't ask for, and this one is an interruption you caused
seconds ago by pressing a button and waiting.

The code is also held for three minutes and served by a second provider, so LightKeyboard
can pin it above the keys instead of you retyping it from memory in another app. Three
minutes because the cost of being too short is typing six digits and the cost of being too
long is a dead code offered in the slot people tap without reading. The expiry is applied
when the value is *read*, not by clearing it on a timer — nothing is guaranteed to be
running to do the clearing, and a value that outlived its window has to answer for itself.

Finding the code is `LoginCodes`, and it is a keyword gate rather than a hunt for digits.
Almost every short number in a message is not a code: a flight number, a buzzer, a time, a
price, the year. So a message has to *say* it is carrying one — "code", "passcode", "OTP",
"verification", "sign in" and a handful more, matched as whole words so "pin" doesn't fire
on "shipping" — and the code is then the number nearest the word that said so, which is what
picks 481920 out of "Your code is 481920. Reply STOP to 44398 to opt out." The WebOTP line
(`@example.com #123456`) skips the gate, being an actual specification rather than a guess.

It is pure Kotlin with no Android in it, and it now has the first unit tests in this repo —
thirteen of them, mostly negative, because finding codes is easy and declining to find one
in a dinner reservation is the entire job. Both CI workflows run them, and the release build
runs them *before* it builds, so a failing test stops the release instead of being noticed
after Obtainium has installed it.
