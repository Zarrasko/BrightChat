package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an alert says. These are the two questions a notification was failing to answer —
 * who sent it, and who reacted — so they are the two things worth pinning down.
 */
class AlertTextTests {

    private val contacts = Contacts.from(
        listOf(
            "+15551234567" to "Alex Rivera",
            "+15559876543" to "Liz Moreno",
        ),
    )

    private fun message(
        text: String = "on my way",
        sender: String? = "+15551234567",
        guid: String = "m1",
        associatedGuid: String? = null,
        associatedType: String? = null,
    ) = ChatMessage(
        guid = guid,
        text = text,
        date = 1_000L,
        fromMe = false,
        sender = sender,
        associatedMessageGuid = associatedGuid,
        associatedMessageType = associatedType,
    )

    @Test
    fun `group message names the sender in the body`() {
        val alert = AlertText.forMessage(
            message = message(),
            isGroup = true,
            chatDisplayName = "Poker Night",
            participants = listOf("+15551234567", "+15559876543"),
            contacts = contacts,
        )
        assertEquals("Poker Night", alert.title)
        assertEquals("Alex: on my way", alert.body)
    }

    /** The title is already the person, so prefixing the body would just repeat it. */
    @Test
    fun `one to one message is not prefixed`() {
        val alert = AlertText.forMessage(
            message = message(),
            isGroup = false,
            chatDisplayName = "",
            participants = listOf("+15551234567"),
            contacts = contacts,
        )
        assertEquals("Alex Rivera", alert.title)
        assertEquals("on my way", alert.body)
    }

    /** An unnamed group is titled by its members — and still says who spoke. */
    @Test
    fun `unnamed group titles by participants and still names the sender`() {
        val alert = AlertText.forMessage(
            message = message(),
            isGroup = true,
            chatDisplayName = "",
            participants = listOf("+15551234567", "+15559876543"),
            contacts = contacts,
        )
        assertEquals("Alex, Liz", alert.title)
        assertEquals("Alex: on my way", alert.body)
    }

    /** The case that used to post a blank line: a tapback carries no text of its own. */
    @Test
    fun `reaction names the reactor and what they reacted to`() {
        val target = message(text = "see you at 6", sender = null, guid = "target").copy(fromMe = true)
        val alert = AlertText.forMessage(
            message = message(text = "", guid = "r1", associatedGuid = "target", associatedType = "love"),
            isGroup = true,
            chatDisplayName = "Poker Night",
            participants = listOf("+15551234567", "+15559876543"),
            contacts = contacts,
            findTarget = { guid -> target.takeIf { guid == "target" } },
        )
        assertEquals("Poker Night", alert.title)
        assertEquals("Alex loved “see you at 6”", alert.body)
    }

    /** iMessage prefixes the target guid with the part it points at; the parse must strip it. */
    @Test
    fun `reaction resolves a part-prefixed target guid`() {
        val target = message(text = "bring cash", guid = "target")
        val alert = AlertText.forMessage(
            message = message(text = "", guid = "r1", associatedGuid = "p:0/target", associatedType = "laugh"),
            isGroup = false,
            chatDisplayName = "",
            participants = listOf("+15551234567"),
            contacts = contacts,
            findTarget = { guid -> target.takeIf { guid == "target" } },
        )
        assertEquals("Alex laughed at “bring cash”", alert.body)
    }

    /** Nothing held locally to point at — say so, rather than saying nothing. */
    @Test
    fun `reaction with an unknown target falls back to a message`() {
        val alert = AlertText.forMessage(
            message = message(text = "", guid = "r1", associatedGuid = "gone", associatedType = "like"),
            isGroup = false,
            chatDisplayName = "",
            participants = listOf("+15551234567"),
            contacts = contacts,
        )
        assertEquals("Alex liked a message", alert.body)
    }

    /** The poll's route: a row, not a message. Same phrasing. */
    @Test
    fun `conversation row names the sender and prefers the reaction`() {
        val convo = Conversation(
            guid = "chat1",
            displayName = "Poker Night",
            participants = listOf("+15551234567", "+15559876543"),
            isGroup = true,
            lastText = "see you at 6",
            lastDate = 1_000L,
            lastFromMe = false,
            lastSender = "+15559876543",
        )
        assertEquals("Liz: see you at 6", AlertText.forConversation(convo, contacts).body)

        val reacted = convo.copy(
            lastReaction = ReactionPreview(
                type = ReactionType.LOVE,
                fromMe = false,
                reactor = "+15551234567",
                target = "“see you at 6”",
            ),
        )
        assertEquals("Alex loved “see you at 6”", AlertText.forConversation(reacted, contacts).body)
    }

    /** A row written by an older build has no sender to name; it must not invent one. */
    @Test
    fun `conversation row with no stored sender is unprefixed`() {
        val convo = Conversation(
            guid = "chat1",
            displayName = "Poker Night",
            participants = listOf("+15551234567", "+15559876543"),
            isGroup = true,
            lastText = "see you at 6",
            lastDate = 1_000L,
            lastFromMe = false,
        )
        assertEquals("see you at 6", AlertText.forConversation(convo, contacts).body)
    }
}
