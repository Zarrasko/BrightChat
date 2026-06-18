package com.craigeley.chat

/**
 * A conversation — BlueBubbles calls it a "chat". The [guid] (e.g.
 * `iMessage;+;chat36268974474180030`) is the key used to fetch its messages and,
 * later, to send into it.
 */
data class Conversation(
    val guid: String,
    val displayName: String,
    val participants: List<String>, // raw handle addresses (phone / email)
    val isGroup: Boolean,
    val lastText: String,
    val lastDate: Long,             // epoch millis; 0 when unknown
    val lastFromMe: Boolean,
) {
    /** Human title: an explicit group name if set, otherwise the participants. */
    val title: String
        get() = when {
            displayName.isNotBlank() -> displayName
            participants.isNotEmpty() -> participants.joinToString(", ")
            else -> "Unknown"
        }
}

/** One message within a conversation. */
data class ChatMessage(
    val guid: String,
    val text: String,
    val date: Long,           // epoch millis
    val fromMe: Boolean,
    val sender: String?,      // handle address; null when from me (shown in groups)
    val attachmentCount: Int,
)

/** A pickable recipient when starting a new message — one row per contact address. */
data class Contact(val name: String, val address: String)

/**
 * A message pushed over the live socket, carried from [com.craigeley.chat.socket.SocketService]
 * to the ViewModel via [com.craigeley.chat.socket.SocketBus]. [isNew] distinguishes
 * a brand-new message from an update (delivered/read/edited). [chatDisplayName] is
 * the embedded chat's group name, used for notifications (the service has no
 * contact index to resolve names).
 */
data class IncomingMessage(
    val chatGuid: String,
    val message: ChatMessage,
    val isNew: Boolean,
    val chatDisplayName: String,
)
