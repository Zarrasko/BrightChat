package com.gios.lightchat.socket

/**
 * Whether the app is currently in the foreground — set from MainActivity's
 * lifecycle. The socket service reads it to decide whether an incoming message
 * should raise a notification (don't notify for what the user is already looking
 * at).
 */
object AppForeground {
    @Volatile
    var active: Boolean = false

    /**
     * Every guid of the thread currently on screen (plural because a forked group spans
     * sibling rooms), empty when the user is on the list. Written by ChatViewModel as
     * threads open and close; read by the socket service and the poll so a message that
     * lands in the thread the user is *watching* is held as already seen — [active] alone
     * can't tell "in the app" from "looking at this very conversation", and that gap is
     * how a reply used to come back as a notification the moment the screen went off.
     */
    @Volatile
    var visibleChatGuids: Set<String> = emptySet()
}
