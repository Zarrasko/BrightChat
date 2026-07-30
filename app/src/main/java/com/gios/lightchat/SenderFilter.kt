package com.gios.lightchat

import android.content.Context
import com.gios.lightchat.api.Store

/**
 * Whether a message is allowed to interrupt.
 *
 * One place, because there are two paths to an alert — the live socket and the catch-up poll —
 * and a filter that disagrees with itself between them is a filter that lets a stranger through
 * whenever the phone happens to have been asleep.
 *
 * This gates the *alert*, never the delivery: a filtered message is still merged into the thread,
 * still bumps the conversation list, still carries its unread mark. It just doesn't buzz, doesn't
 * light the panel and doesn't put a box in front of what you were doing.
 */
object SenderFilter {

    /**
     * @param known whether the sender is in the address book, by [Contacts.knows]'s definition —
     *   a named group, or any participant with a name. Passed in rather than derived here because
     *   the two callers already hold different shapes of the same fact: the socket has a single
     *   incoming message, the poll has a whole conversation.
     */
    fun mayAlert(context: Context, known: Boolean): Boolean =
        known || Store.notifyUnknown(context)

    /**
     * The socket's version of "known", from a live message.
     *
     * A non-blank chat name means a named group, which counts as known whoever is speaking in it —
     * matching `Contacts.knows`, and matching the intuition that a group you named is not a
     * stranger. Otherwise it comes down to whether the sender resolves to a name.
     */
    fun knownSender(contacts: Contacts, chatDisplayName: String, sender: String?): Boolean =
        chatDisplayName.isNotBlank() || (sender != null && contacts.name(sender) != null)
}
