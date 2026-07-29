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
}
