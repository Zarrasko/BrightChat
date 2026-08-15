package com.gios.lightchat

/**
 * Folding a live message into the open thread's raw list — **one row per guid, always.**
 *
 * A send puts an optimistic row up straight away, keyed by the client `tempGuid` (see
 * `ChatViewModel.sendPicked`), and the real message arrives twice afterwards: once as the
 * HTTP send's return value, once as the socket's `new-message` event, followed by an
 * `updated-message` for each delivery/read stamp. Any of those can land first, so the merge
 * has to match an incoming message against *either* the guid it now carries or the tempGuid
 * the optimistic row is still keyed by.
 *
 * The trap this exists to close: matching by tempGuid finds the optimistic row even when the
 * real row is already in the list — the socket echo appended it while the blocking send was
 * still uploading. Writing the real message over the optimistic slot then leaves **two rows
 * under one guid**, and the thread's `LazyColumn` does not tolerate that; it throws
 * `Key "…" was already used` and takes the app down. So the merge writes the matched slot and
 * drops every other row carrying that guid, which makes a duplicate key unrepresentable
 * rather than unlikely.
 *
 * No Android imports on purpose — this is the part worth testing, and it is tested in
 * `ThreadMergeTest`.
 */
internal fun mergeIntoThread(list: List<ChatMessage>, incoming: ChatMessage): List<ChatMessage> {
    val idx = list.indexOfFirst {
        it.guid == incoming.guid || (incoming.tempGuid != null && it.guid == incoming.tempGuid)
    }
    if (idx < 0) return list + incoming
    val out = ArrayList<ChatMessage>(list.size)
    for ((i, existing) in list.withIndex()) {
        when {
            // The message's place in the thread is where it already sits — writing it
            // here rather than appending keeps a send from jumping to the bottom twice.
            i == idx -> out.add(incoming)
            // The same message under a second row: the echo that arrived while the
            // optimistic copy was still up. It is the one being written above.
            existing.guid == incoming.guid -> Unit
            else -> out.add(existing)
        }
    }
    return out
}
