package com.gios.lightchat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One row per guid in the open thread, whatever order the send's three answers arrive in
 * (HTTP return, socket `new-message`, socket `updated-message`). Two rows under one guid
 * is not a cosmetic double — the thread's LazyColumn throws on it.
 */
class ThreadMergeTest {

    private fun message(
        guid: String,
        text: String = "hi",
        date: Long = 1_000L,
        tempGuid: String? = null,
        dateDelivered: Long = 0L,
    ) = ChatMessage(
        guid = guid,
        text = text,
        date = date,
        fromMe = true,
        sender = null,
        tempGuid = tempGuid,
        dateDelivered = dateDelivered,
    )

    @Test
    fun `an unseen message is appended`() {
        val list = listOf(message("A"))
        val out = mergeIntoThread(list, message("B"))
        assertEquals(listOf("A", "B"), out.map { it.guid })
    }

    @Test
    fun `the echo replaces the optimistic row in place`() {
        val list = listOf(message("A"), message("temp-1-2", text = "[Photo]"))
        val out = mergeIntoThread(list, message("REAL", tempGuid = "temp-1-2"))
        assertEquals(listOf("A", "REAL"), out.map { it.guid })
    }

    @Test
    fun `an update to a message already held replaces it, once`() {
        val list = listOf(message("A"), message("REAL"))
        val out = mergeIntoThread(list, message("REAL", dateDelivered = 5L))
        assertEquals(listOf("A", "REAL"), out.map { it.guid })
        assertEquals(5L, out.last().dateDelivered)
    }

    /**
     * light-reports#21. Sending several photos: the socket echo appended the real row while
     * the blocking upload was still running, so the optimistic row and the real row were both
     * in the list. The delivery update then matched the optimistic row by tempGuid and wrote
     * the real message over it — two rows, one guid, and the thread died on the next frame.
     */
    @Test
    fun `an update matching by tempGuid collapses the row the echo already appended`() {
        val list = listOf(
            message("A"),
            message("temp-1-2", text = "[Photo]"),
            message("REAL", tempGuid = "temp-1-2"),
        )
        val out = mergeIntoThread(list, message("REAL", tempGuid = "temp-1-2", dateDelivered = 5L))
        assertEquals(listOf("A", "REAL"), out.map { it.guid })
        assertEquals(out.map { it.guid }.distinct(), out.map { it.guid })
        assertEquals(5L, out.last().dateDelivered)
    }

    @Test
    fun `several photos in flight keep one row each`() {
        var list = listOf<ChatMessage>()
        // Three optimistic rows, then each one's echo and delivery update, interleaved.
        for (i in 1..3) list = mergeIntoThread(list, message("temp-$i", text = "[Photo]"))
        for (i in 1..3) list = mergeIntoThread(list, message("G$i", tempGuid = "temp-$i"))
        for (i in 1..3) list = mergeIntoThread(list, message("G$i", tempGuid = "temp-$i", dateDelivered = 9L))
        assertEquals(listOf("G1", "G2", "G3"), list.map { it.guid })
    }
}
