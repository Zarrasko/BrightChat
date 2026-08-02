package com.gios.lightchat

/**
 * Holding a few starred conversations at the top of the starred list.
 *
 * **A pin is an ordering, not a second star.** Starring already answers "does this belong on the
 * front page"; pinning answers "and in what order", which is a different question and only a
 * meaningful one about things that are already there. So only a starred chat can be pinned, and
 * unstarring drops the pin with it — otherwise the app would hold an order for a list the chat
 * is no longer in, and re-starring months later would resurrect a position nobody chose.
 *
 * **Pin order is the order you pinned in, newest first.** Not the order of the list underneath,
 * which moves every time somebody messages you — the whole reason to pin something is that you
 * want it in a place that does not move.
 *
 * Kept by guid rather than by index, and no Android in here: the ordering is the part worth a
 * test, and the storage is a newline-joined string in [com.gios.lightchat.api.Store] like the
 * starred list beside it.
 */
object Pins {

    /**
     * How many pins are allowed.
     *
     * A cap, because a pinned list long enough to scroll is just the starred list again with an
     * extra rule attached. Five is about a screenful above the fold on this panel.
     */
    const val MAX = 5

    /**
     * [starred] reordered so the pinned ones lead, in pin order.
     *
     * @param order the pinned guids, newest pin first.
     *
     * A pin naming a conversation that isn't in [starred] is skipped rather than dropped from
     * storage here — this function only decides an order, and a chat can be absent because the
     * list is still syncing rather than because it was unstarred. [prune] is where forgetting
     * happens, and it is called from the one place that knows a star was actually removed.
     */
    fun order(starred: List<Conversation>, order: List<String>): List<Conversation> {
        if (order.isEmpty()) return starred
        val byGuid = starred.associateBy { it.guid }
        val pinned = order.mapNotNull { byGuid[it] }
        if (pinned.isEmpty()) return starred
        val taken = pinned.mapTo(HashSet()) { it.guid }
        return pinned + starred.filter { it.guid !in taken }
    }

    /**
     * [order] with [guid] pinned to the front, or unpinned if it was already there.
     *
     * Re-pinning something already pinned moves it to the front rather than doing nothing, so
     * the gesture is also how you reorder — there is no drag on this panel and there should not
     * be one.
     */
    fun toggle(order: List<String>, guid: String): List<String> {
        if (guid.isBlank()) return order
        if (guid in order) return order - guid
        return (listOf(guid) + order).take(MAX)
    }

    /** Whether [guid] is pinned. */
    fun isPinned(order: List<String>, guid: String): Boolean = guid in order

    /** [order] with anything no longer starred forgotten. Called when a star is removed. */
    fun prune(order: List<String>, starred: Set<String>): List<String> =
        order.filter { it in starred }
}
