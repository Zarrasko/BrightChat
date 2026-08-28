package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Who a room is, when the event that woke the phone only knows half of it.
 *
 * The case that matters is the first one: an unnamed group over the socket, which arrived with no
 * name and an empty participant list and was titled "Unknown" on every notification it ever sent.
 * See [RoomIdentity] for why the payload is like that.
 */
class RoomIdentityTest {

    private val contacts = Contacts.from(
        listOf(
            "+15551234567" to "Alex Rivera",
            "+15559876543" to "Liz Moreno",
        ),
    )

    private fun of(
        eventName: String = "",
        eventParticipants: List<String> = emptyList(),
        storedName: String? = null,
        storedParticipants: List<String> = emptyList(),
        nickname: String? = null,
        sender: String? = "+15551234567",
    ) = RoomIdentity.of(
        eventName = eventName,
        eventParticipants = eventParticipants,
        storedName = storedName,
        storedParticipants = storedParticipants,
        nickname = nickname,
        sender = sender,
    )

    @Test
    fun `an unnamed group takes its people from the stored row`() {
        val room = of(storedParticipants = listOf("+15551234567", "+15559876543"))
        assertEquals("", room.name)
        assertEquals("Alex, Liz", contacts.title(room.name, room.participants))
    }

    /** The whole bug, stated as the title it used to produce. */
    @Test
    fun `an unnamed group is no longer titled Unknown`() {
        val room = of(storedParticipants = listOf("+15559876543"))
        assertEquals("Liz Moreno", contacts.title(room.name, room.participants))
    }

    @Test
    fun `the event wins over the stored row for a group just renamed`() {
        val room = of(
            eventName = "Poker Night",
            eventParticipants = listOf("+15551234567"),
            storedName = "Poker",
            storedParticipants = listOf("+15559876543"),
        )
        assertEquals("Poker Night", room.name)
        assertEquals(listOf("+15551234567"), room.participants)
    }

    @Test
    fun `a nickname wins over both`() {
        assertEquals(
            "The Boys",
            of(eventName = "Poker Night", storedName = "Poker", nickname = "The Boys").name,
        )
    }

    /** org.json turns BlueBubbles' JSON null into this, and it is not blank. */
    @Test
    fun `the string null is not a name`() {
        assertEquals("Poker", of(eventName = "null", storedName = "Poker").name)
        assertEquals("", of(eventName = "null", storedName = "null").name)
        assertEquals("", of(nickname = "  ").name)
    }

    /** A group nobody has swept yet: one name beats the word "Unknown". */
    @Test
    fun `a room with nothing known falls back to the sender`() {
        val room = of()
        assertEquals(listOf("+15551234567"), room.participants)
        assertEquals("Alex Rivera", contacts.title(room.name, room.participants))
    }

    @Test
    fun `a room with nothing at all is still empty`() {
        assertEquals(emptyList<String>(), of(sender = null).participants)
        assertEquals(emptyList<String>(), of(sender = "").participants)
    }

    /** `Contacts.knows`' rule, which the socket could not apply before. */
    @Test
    fun `a known member makes an unnamed group known`() {
        val room = of(storedParticipants = listOf("+19995550000", "+15559876543"), sender = "+19995550000")
        assertEquals(
            true,
            SenderFilter.knownSender(contacts, room.name, "+19995550000", room.participants),
        )
        assertEquals(
            false,
            SenderFilter.knownSender(contacts, "", "+19995550000", listOf("+19995550000")),
        )
    }
}
