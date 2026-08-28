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
     * @param carriesCode whether the message is a one-time login code ([LoginCodes]).
     *
     * **A login code always alerts, stranger or not**, and that is the one exception in here.
     * The whole reason a code arrives from a number you have never seen is that it was generated
     * for you seconds ago by something you are sitting in front of, waiting. Every other unknown
     * sender is an interruption you did not ask for; this one is an interruption you caused, and
     * silencing it makes the setting that silences strangers quietly break logging in — which is
     * the worst kind of bug, because nothing appears to fail and the cause is a preference set
     * weeks earlier.
     *
     * It does not widen anything else: the code still has to survive [LoginCodes.find], which
     * requires the message to say in words that it is carrying one.
     */
    fun mayAlert(context: Context, known: Boolean, carriesCode: Boolean = false): Boolean =
        known || carriesCode || Store.notifyUnknown(context)

    /**
     * The socket's version of "known", from a live message.
     *
     * A non-blank chat name means a named group, which counts as known whoever is speaking in it —
     * matching `Contacts.knows`, and matching the intuition that a group you named is not a
     * stranger. Otherwise it comes down to whether the sender resolves to a name, or any of
     * [participants] does.
     *
     * The participants are the half that used to be missing, and it mattered twice. This is
     * `Contacts.knows`'s rule — "a group is Known as soon as one member is" — and without them the
     * socket disagreed with the poll about the same room: an unnamed group of people you know, with
     * a number you don't speaking in it, was a stranger to the live path and a friend to the
     * catch-up. Which of those you got depended on whether the phone happened to be awake. The
     * socket could not apply the rule at all until [RoomIdentity] gave it a participant list; see
     * there for why an event on its own has none.
     */
    fun knownSender(
        contacts: Contacts,
        chatDisplayName: String,
        sender: String?,
        participants: List<String> = emptyList(),
    ): Boolean = chatDisplayName.isNotBlank() ||
        (sender != null && contacts.name(sender) != null) ||
        participants.any { contacts.name(it) != null }
}
