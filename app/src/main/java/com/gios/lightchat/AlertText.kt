package com.gios.lightchat

/**
 * The words on an alert: what its title says, and what its body says.
 *
 * There are three routes to an alert — the live socket, the background [CatchUp] poll, and
 * the deferred [PendingAlerts] flush — and each used to phrase things itself. Where they
 * agreed they agreed by accident, and where they didn't the same message read differently
 * depending on whether the phone happened to be awake when it arrived. So the phrasing
 * lives here, in one place, and the routes only supply what they know.
 *
 * Two things it fixes, both of them "who?":
 *
 * - **A group message never said who sent it.** The title is the room ("Poker Night"), so
 *   the sender's name has nowhere else to go, and the body was the bare text. Now the body
 *   is `Alex: on my way`, the same shape iMessage uses.
 * - **A tapback said nothing at all.** A tapback arrives as a message whose own `text` is
 *   empty — the reaction is in `associatedMessageType`, and what it points at is in
 *   `associatedMessageGuid` — so the notification was a title and a blank line. Now it
 *   reads `Alex loved “see you at 6”`, which needs the *target* message; hence
 *   [findTarget], which the caller resolves out of the local store (a reaction's target is
 *   nearly always something recent, and "a message" is the honest fallback when it isn't).
 *
 * A reaction *removal* is deliberately not phrased: it isn't an event worth a buzz, and
 * the callers drop it before getting here.
 */
object AlertText {

    /** A composed alert: [title] is the room, [body] the line under it. */
    data class Alert(val title: String, val body: String)

    /**
     * The alert for a message we hold in full — the socket's case.
     *
     * [findTarget] resolves a guid to the message a tapback points at; it may return null
     * for anything not held locally.
     */
    fun forMessage(
        message: ChatMessage,
        isGroup: Boolean,
        chatDisplayName: String,
        participants: List<String>,
        contacts: Contacts,
        findTarget: (String) -> ChatMessage? = { null },
    ): Alert {
        val title = contacts.title(chatDisplayName, participants)
        val reaction = message.reactionPreview(findTarget)?.summary(contacts)
        return Alert(
            title = title,
            body = reaction ?: body(message.previewText, isGroup, message.sender, contacts),
        )
    }

    /**
     * The alert for a conversation *row* — the poll's case, which works from the list and
     * so has the last message's text but not the message.
     *
     * A row whose newest activity is a tapback carries it as [Conversation.lastReaction],
     * already resolved against the sweep's own messages, and that is what the body says;
     * [Conversation.lastText] still holds the older real message underneath and would name
     * the wrong thing.
     */
    fun forConversation(conversation: Conversation, contacts: Contacts): Alert = Alert(
        title = contacts.title(conversation),
        body = conversation.lastReaction?.summary(contacts)
            ?: body(conversation.lastText, conversation.isGroup, conversation.lastSender, contacts),
    )

    /**
     * `Alex: on my way` in a group, the bare text in a 1:1 — where the title is already
     * the person's name and a prefix would only repeat it.
     *
     * The sender is prefixed even in an unnamed group, whose title is the participants:
     * "Alex, Liz" as a title and `Alex: on my way` under it is how iMessage reads too, and
     * the alternative is a message from a three-way group looking like a 1:1.
     */
    private fun body(text: String, isGroup: Boolean, sender: String?, contacts: Contacts): String {
        if (!isGroup || sender == null) return text
        val who = contacts.sender(sender)
        return if (text.isBlank()) who else "$who: $text"
    }
}
