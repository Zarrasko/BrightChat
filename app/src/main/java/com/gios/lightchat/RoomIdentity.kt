package com.gios.lightchat

/**
 * Who a room is, assembled from everything the phone knows about it.
 *
 * ### The bug this exists for
 *
 * Every message from an **unnamed group** arrived as "Unknown". Not once — always, for the whole
 * life of the chat, on the notification, in the heads-up box and in the alert held for the screen
 * going off.
 *
 * The socket event describes the chat as the server chose to serialize it, and BlueBubbles does not
 * include `participants` unless it is asked to: the REST calls name `chats.participants` in their
 * `with` array (see `BlueBubblesApi.messagePage`) and a *push* cannot ask for anything. The one
 * fallback in `BlueBubblesApi.chatParticipants` reads `chatIdentifier`, which is the other party's
 * address for a 1:1 and the literal string `chat36268974474180030` for a group — so it is
 * deliberately rejected there, correctly, and a group came out of the socket with no name and
 * nobody in it. `Contacts.title("", emptyList())` has one answer for that, and it is "Unknown".
 *
 * The missing half was never far away: the same room has already been through the REST sweep, and
 * the stored row *does* carry its participants. So the event supplies what only it knows — that a
 * message arrived — and the store supplies who the room is.
 *
 * ### The order
 *
 * A local nickname first, because it is the name this phone was told to use and it already wins in
 * the conversation list ([Conversation.title]); then the event, which is the freshest word on a
 * room that may have just been renamed; then the store. Participants come from the event, then the
 * store, and last of all from the sender alone — a group nobody has swept yet is titled by the one
 * person in it the phone can name, because a name is worth more on a lock screen than the word
 * "Unknown", and the sweep that follows the message fixes the row within seconds either way.
 *
 * Its own object, with no `Context` and no store, so all of that can be checked without a phone.
 */
object RoomIdentity {

    /** A room's name (blank when it has none) and everyone in it. Feeds [Contacts.title]. */
    data class Room(val name: String, val participants: List<String>)

    fun of(
        eventName: String,
        eventParticipants: List<String>,
        storedName: String?,
        storedParticipants: List<String>,
        nickname: String?,
        sender: String?,
    ): Room = Room(
        name = real(nickname) ?: real(eventName) ?: real(storedName) ?: "",
        participants = eventParticipants
            .ifEmpty { storedParticipants }
            .ifEmpty { listOfNotNull(sender?.takeIf { it.isNotBlank() }) },
    )

    /**
     * A name that is actually a name.
     *
     * `"null"` is guarded as well as blank, for the reason `Conversation.named()` gives: BlueBubbles
     * sends a JSON null for an unnamed chat, org.json stringifies it, and a cache written before
     * that was handled can still hold it. Untreated it is not merely ugly — it is non-blank, so it
     * would win this whole ordering and title the notification `null`.
     */
    private fun real(name: String?): String? =
        name?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
}
