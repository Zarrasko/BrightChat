package com.gios.lightchat

import com.gios.lightchat.dial.AddressBook
import com.gios.lightchat.dial.T9
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class T9Test {

    @Test
    fun `the mapping is the one printed on the keys`() {
        assertEquals('2', T9.digitFor('a'))
        assertEquals('2', T9.digitFor('C'))
        assertEquals('7', T9.digitFor('s'))
        assertEquals('9', T9.digitFor('z'))
        // 1 and 0 carry no letters, and a digit is not a letter.
        assertEquals(null, T9.digitFor('1'))
        assertEquals(null, T9.digitFor(' '))
        assertEquals(null, T9.digitFor('-'))
    }

    @Test
    fun `a name matches from the start of any word`() {
        assertTrue(T9.matchesName("Giovanni Lupo", "446"))   // gio
        assertTrue(T9.matchesName("Giovanni Lupo", "58"))    // lu
        assertTrue(T9.matchesName("Mary-Jane Watson", "526")) // jan, after the hyphen
        assertTrue(T9.matchesName("O'Brien", "27"))          // br, after the apostrophe
    }

    @Test
    fun `never in the middle of a word`() {
        // "up" is inside Lupo and starts no word in it. An infix match returns half the address
        // book for two digits, which is the search failing at its only job.
        assertFalse(T9.matchesName("Giovanni Lupo", "87"))
        assertFalse(T9.matchesName("Watson", "287"))
    }

    @Test
    fun `a run does not span a space`() {
        // "Gia Nni" is reachable as two word starts; 4265 would have to cross the gap.
        // "Gia" is 4-4-2; crossing into "Nni" would need the space to carry a digit.
        assertFalse(T9.matchesName("Gia Nni", "4426"))
        assertTrue(T9.matchesName("Gia Nni", "442"))
        assertTrue(T9.matchesName("Gia Nni", "66"))
    }

    @Test
    fun `an empty query matches everything and letters match nothing`() {
        assertTrue(T9.matchesName("Anybody", ""))
        // A query with letters in it isn't keypad input and must not be read as T9.
        assertFalse(T9.matchesName("Anybody", "an"))
    }

    @Test
    fun `numbers match anywhere, comparing digits only`() {
        assertTrue(T9.matchesNumber("+1 (315) 212-2695", "2122695"))
        assertTrue(T9.matchesNumber("+13152122695", "315"))
        // The tail is what people remember, so a prefix rule would be the wrong one.
        assertTrue(T9.matchesNumber("+13152122695", "2695"))
        assertFalse(T9.matchesNumber("+13152122695", "0000"))
    }
}

class AddressBookTest {

    private fun row(id: Long, name: String, number: String, mobile: Boolean = false, primary: Boolean = false) =
        AddressBook.Row(id, name, number, mobile = mobile, superPrimary = primary)

    @Test
    fun `rows collapse into people`() {
        val out = AddressBook.merge(
            listOf(
                row(1, "Alex", "608-264-6591"),
                row(1, "Alex", "+16082646591"),   // the same number, written differently
                row(2, "Basil", "212-555-0148"),
            ),
        )
        assertEquals(listOf("Alex", "Basil"), out.map { it.name })
        assertEquals(1, out[0].numbers.size)
    }

    @Test
    fun `the default and the mobile come first`() {
        val out = AddressBook.merge(
            listOf(
                row(1, "Alex", "111-111-1111"),
                row(1, "Alex", "222-222-2222", mobile = true),
                row(1, "Alex", "333-333-3333", primary = true),
            ),
        )
        assertEquals("333-333-3333", out[0].numbers[0].raw)
        assertEquals("222-222-2222", out[0].numbers[1].raw)
    }

    @Test
    fun `a nameless row is titled by its number`() {
        val out = AddressBook.merge(listOf(row(9, "  ", "212-555-0148")))
        assertEquals("212-555-0148", out[0].name)
    }

    @Test
    fun `name matches sort above number matches`() {
        val gia = AddressBook.merge(listOf(row(1, "Gia", "999-999-9999")))
        val zed = AddressBook.merge(listOf(row(2, "Zed", "442-000-0000")))
        // "Gia" is 4-4-2 by name; Zed only has it in his number.
        val out = AddressBook.search(gia + zed, "442")
        assertEquals(listOf("Gia", "Zed"), out.map { it.name })
    }

    @Test
    fun `people only BlueBubbles knows are folded in`() {
        val phone = AddressBook.merge(listOf(row(1, "Alex", "608-264-6591")))
        // Keys as Contacts.asMap stores them: last ten digits, lowercase for an email.
        val out = AddressBook.withKnown(
            phone,
            mapOf(
                "6082646591" to "Alex From The Mac",   // already on the phone; the phone wins
                "3152122695" to "Liz",                 // only on the Mac
                "basil@icloud.com" to "Basil",         // an email; nothing to dial
            ),
        )
        assertEquals(listOf("Alex", "Liz"), out.map { it.name })
        // The phone's row survives intact rather than being replaced by a name-only one.
        assertEquals("608-264-6591", out[0].numbers[0].raw)
        assertEquals(AddressBook.FROM_MESSAGES, out[1].numbers[0].label)
    }

    @Test
    fun `synthetic ids are negative, stable and distinct`() {
        val known = mapOf("3152122695" to "Liz", "2125550148" to "Zoe")
        val once = AddressBook.withKnown(emptyList(), known)
        val twice = AddressBook.withKnown(emptyList(), known)
        assertEquals(once.map { it.id }, twice.map { it.id })
        assertEquals(2, once.map { it.id }.toSet().size)
        assertTrue(once.all { it.id < 0 })
    }

    @Test
    fun `no index changes nothing`() {
        val phone = AddressBook.merge(listOf(row(1, "Alex", "1112223333")))
        assertEquals(phone, AddressBook.withKnown(phone, emptyMap()))
    }

    @Test
    fun `an empty query is the whole address book`() {
        val all = AddressBook.merge(listOf(row(1, "Alex", "1112223333")))
        assertEquals(all, AddressBook.search(all, ""))
    }
}

class PinsTest {

    private fun chat(guid: String) = Conversation(
        guid = guid,
        displayName = guid,
        participants = listOf(guid),
        isGroup = false,
        lastText = "",
        lastDate = 0L,
        lastFromMe = false,
    )

    @Test
    fun `pinned lead in pin order, the rest keep theirs`() {
        val starred = listOf(chat("a"), chat("b"), chat("c"), chat("d"))
        val out = Pins.order(starred, listOf("c", "a"))
        assertEquals(listOf("c", "a", "b", "d"), out.map { it.guid })
    }

    @Test
    fun `no pins changes nothing`() {
        val starred = listOf(chat("a"), chat("b"))
        assertEquals(starred, Pins.order(starred, emptyList()))
    }

    @Test
    fun `a pin for a chat that isn't here is skipped, not fatal`() {
        val starred = listOf(chat("a"))
        assertEquals(listOf("a"), Pins.order(starred, listOf("gone", "a")).map { it.guid })
    }

    @Test
    fun `toggling pins to the front, and again unpins`() {
        assertEquals(listOf("b", "a"), Pins.toggle(listOf("a"), "b"))
        assertEquals(listOf("a"), Pins.toggle(listOf("b", "a"), "b"))
        // Re-pinning something already at the back moves it to the front — the gesture that
        // pins is also the only way to reorder, since there is no drag on this panel.
        assertEquals(listOf("a", "b"), Pins.toggle(Pins.toggle(listOf("b", "a"), "a"), "a"))
    }

    @Test
    fun `the cap holds`() {
        var pins = emptyList<String>()
        for (i in 1..8) pins = Pins.toggle(pins, "chat$i")
        assertEquals(Pins.MAX, pins.size)
        assertEquals("chat8", pins.first())
    }

    @Test
    fun `unstarring forgets the pin`() {
        assertEquals(listOf("a"), Pins.prune(listOf("a", "b"), setOf("a")))
    }
}
