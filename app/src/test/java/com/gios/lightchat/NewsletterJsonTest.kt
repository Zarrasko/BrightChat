package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The batches are the one piece of newsletter state that survives a reboot, and the only way
 * they can be lost is this codec. Pure Kotlin + org.json, so it runs on the JVM.
 */
class NewsletterJsonTest {

    @Test
    fun `round trips a batch of both kinds`() {
        val batches = listOf(
            NewsletterBatch(
                id = "nl-1",
                name = "Family",
                targets = listOf(
                    NewsletterTarget(id = "iMessage;+;chat123", label = "Lupo Family", isChat = true),
                    NewsletterTarget(id = "+15551234567", label = "Alex", isChat = false),
                ),
            ),
            NewsletterBatch(id = "nl-2", name = "", targets = emptyList()),
        )
        assertEquals(batches, NewsletterJson.decode(NewsletterJson.encode(batches)))
    }

    @Test
    fun `a chat and a contact with the same id are different recipients`() {
        val chat = NewsletterTarget(id = "x", label = "X", isChat = true)
        val contact = NewsletterTarget(id = "x", label = "X", isChat = false)
        assertTrue(chat.key != contact.key)
    }

    @Test
    fun `garbage and absent storage decode to nothing rather than throwing`() {
        assertEquals(emptyList<NewsletterBatch>(), NewsletterJson.decode(null))
        assertEquals(emptyList<NewsletterBatch>(), NewsletterJson.decode(""))
        assertEquals(emptyList<NewsletterBatch>(), NewsletterJson.decode("{not json"))
        // A shape from some future build: entries missing an id are dropped, the rest survive.
        assertEquals(
            listOf(NewsletterBatch(id = "keep", name = "Keep")),
            NewsletterJson.decode("""[{"name":"lost"},{"id":"keep","name":"Keep"}]"""),
        )
    }

    @Test
    fun `a target with no label falls back to its id`() {
        val decoded = NewsletterJson.decode("""[{"id":"b","name":"B","targets":[{"id":"+15550000000"}]}]""")
        assertEquals("+15550000000", decoded.single().targets.single().label)
        assertTrue(!decoded.single().targets.single().isChat)
    }

    @Test
    fun `summary names the first few and counts the rest`() {
        val many = (1..5).map { NewsletterTarget(id = "$it", label = "P$it", isChat = false) }
        assertEquals("P1, P2, P3 +2 more", NewsletterBatch("id", "Big", many).summary())
        assertEquals("P1, P2", NewsletterBatch("id", "Small", many.take(2)).summary())
        assertEquals("No recipients", NewsletterBatch("id", "Empty").summary())
    }

    private fun progress(
        sent: Int,
        total: Int,
        failed: List<String> = emptyList(),
        partial: List<String> = emptyList(),
        done: Boolean = false,
        error: String? = null,
    ) = NewsletterProgress("nl-1", "B", sent, total, failed, partial, done, error)

    @Test
    fun `progress reads as progress while running and as an outcome when done`() {
        assertEquals("Sending 2/5…", progress(2, 5).line())
        assertEquals("Sent to 5", progress(5, 5, done = true).line())
        assertEquals(
            "Sent to 4 — failed: Alex",
            progress(4, 5, failed = listOf("Alex"), done = true).line(),
        )
        assertEquals(
            "Couldn’t send to anyone — failed: A, B",
            progress(0, 2, failed = listOf("A", "B"), done = true).line(),
        )
    }

    /** Got the words but not the photos is its own outcome — re-running the batch to fix it
     *  would double-send the text to everybody who succeeded. */
    @Test
    fun `a partial recipient is counted as sent and named separately`() {
        assertEquals(
            "Sent to 5 — photos missing: Alex",
            progress(5, 5, partial = listOf("Alex"), done = true).line(),
        )
        assertEquals(
            "Sent to 4 — failed: Bob — photos missing: Alex",
            progress(4, 5, failed = listOf("Bob"), partial = listOf("Alex"), done = true).line(),
        )
    }

    /** A broadcast that never started says why, instead of reporting zeroes that look like
     *  forty failed sends. */
    @Test
    fun `an error replaces the whole line`() {
        assertEquals(
            "Another batch is still sending",
            progress(0, 40, done = true, error = "Another batch is still sending").line(),
        )
    }
}
