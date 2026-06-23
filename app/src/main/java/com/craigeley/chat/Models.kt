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

/**
 * One file riding along with a message. [guid] keys the download endpoint; the
 * raw bytes are fetched + cached lazily (see [com.craigeley.chat.Attachments]).
 * Images render inline; other files render as a tappable [fileLabel] row that opens
 * them externally (see `ChatViewModel.openAttachment`).
 */
data class Attachment(
    val guid: String,
    val mimeType: String?,
    val transferName: String?,
    val width: Int,           // pixels per the server's metadata; 0 when unknown
    val height: Int,
) {
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true

    /** A short human type for a non-image file, e.g. "Video", "Audio", "Contact". */
    val typeLabel: String
        get() = when {
            mimeType == null -> "File"
            mimeType.startsWith("video/") -> "Video"
            mimeType.startsWith("audio/") -> "Audio"
            mimeType.contains("vcard") -> "Contact"
            mimeType == "application/pdf" -> "PDF"
            mimeType.startsWith("image/") -> "Photo"
            else -> "File"
        }

    /** The row shown for a non-image file in the thread: type plus the filename. */
    val fileLabel: String
        get() = transferName?.takeIf { it.isNotBlank() }?.let { "$typeLabel · $it" } ?: typeLabel
}

/**
 * The six iMessage tapbacks. [apiValue] is the string both the `message/react`
 * endpoint takes *and* the value the server reports in `associatedMessageType` —
 * it transforms the raw iMessage code to a word (2000→`love`, 2003→`laugh`,
 * 3000→`-love` for a removal). The visual mark for each is drawn/typeset in
 * `ui/Tapbacks.kt` (heart/thumbs as vectors, HA/‼/? as Public Sans text).
 */
enum class ReactionType(val apiValue: String) {
    LOVE("love"),
    LIKE("like"),
    DISLIKE("dislike"),
    LAUGH("laugh"),
    EMPHASIZE("emphasize"),
    QUESTION("question");

    companion object {
        /** Maps a server `associatedMessageType` string (`love` or `-love`) to a
         *  type, ignoring the add/remove sign. Null if it isn't a reaction (e.g.
         *  `sticker`, or null/blank on a normal message). */
        fun fromApiValue(raw: String?): ReactionType? {
            if (raw.isNullOrBlank()) return null
            val base = raw.removePrefix("-")
            return entries.firstOrNull { it.apiValue == base }
        }
    }
}

/** A tapback folded onto its target message: who reacted and with what. */
data class Reaction(
    val type: ReactionType,
    val fromMe: Boolean,
    val sender: String?,      // reactor's handle address; null when from me
)

/** One message within a conversation. */
data class ChatMessage(
    val guid: String,
    val text: String,
    val date: Long,           // epoch millis
    val fromMe: Boolean,
    val sender: String?,      // handle address; null when from me (shown in groups)
    val attachments: List<Attachment> = emptyList(),
    // Set only on reaction messages: the message this tapback targets (raw, may be
    // prefixed `p:0/` or `bp:`) and its `associatedMessageType` (the server's word
    // form, e.g. `love`/`-love`). Such messages aren't shown as rows — the ViewModel
    // folds them onto their target as [reactions].
    val associatedMessageGuid: String? = null,
    val associatedMessageType: String? = null,
    // Tapbacks folded onto this (normal) message for display. Never serialized;
    // populated by ChatViewModel.foldReactions from the reaction messages.
    val reactions: List<Reaction> = emptyList(),
) {
    val images: List<Attachment> get() = attachments.filter { it.isImage }

    /** Non-image attachments — rendered as tappable file rows, not inline. */
    val files: List<Attachment> get() = attachments.filter { !it.isImage }

    /** This message is itself a tapback (folded onto its target, not shown alone). */
    val isReaction: Boolean
        get() = !associatedMessageGuid.isNullOrBlank() && reactionType != null

    /** The tapback this message carries, if it is one. */
    val reactionType: ReactionType? get() = ReactionType.fromApiValue(associatedMessageType)

    /** Whether this tapback *removes* a reaction (server prefixes the word with `-`). */
    val isReactionRemoval: Boolean get() = associatedMessageType?.startsWith("-") == true

    /** The plain guid of the message this tapback targets, stripping iMessage's
     *  `p:<n>/` (part) and `bp:` (body) prefixes. */
    val reactionTargetGuid: String?
        get() {
            val raw = associatedMessageGuid ?: return null
            raw.indexOf('/').let { if (it >= 0) return raw.substring(it + 1) }
            if (raw.startsWith("bp:")) return raw.substring(3)
            return raw
        }

    /** The body line to render, or null when the text is just the attachment
     *  placeholder for attachment(s) we render ourselves (images inline, other
     *  files as their own tappable rows). */
    val bodyText: String?
        get() = if (text == ATTACHMENT_PLACEHOLDER && attachments.isNotEmpty()) null else text.ifEmpty { null }

    /**
     * One-line summary for the conversation list: the message text when there is
     * any, or — for an attachment-only message — a bracketed description of it
     * (`[Photo]`, `[3 Photos]`, `[Attachment]`). The brackets mark it as a descriptor
     * so it can't be mistaken for someone literally texting "photo". Empty only for a
     * genuinely empty message.
     */
    val previewText: String
        get() {
            val t = if (text == ATTACHMENT_PLACEHOLDER) "" else text
            if (t.isNotBlank()) return t
            val imageCount = images.size
            if (imageCount > 0) return if (imageCount == 1) "[Photo]" else "[$imageCount Photos]"
            // No images here, so any attachments are non-image files; a single one
            // gets its type ("[Video]"), several get a count.
            if (attachments.size == 1) return "[${attachments.first().typeLabel}]"
            if (attachments.isNotEmpty()) return "[${attachments.size} Attachments]"
            // Placeholder text but no parsed attachments (e.g. an optimistic fallback).
            return if (text == ATTACHMENT_PLACEHOLDER) "[Attachment]" else ""
        }

    companion object {
        /** Stand-in body for an attachment-only message (no real text). */
        const val ATTACHMENT_PLACEHOLDER = "[Attachment]"
    }
}

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

/**
 * A typing-indicator change from the live socket ([com.craigeley.chat.socket.SocketBus]):
 * the other party in [chatGuid] started ([typing] true) or stopped typing. The
 * server only emits these for 1:1 chats, and re-emits roughly every 5s while typing
 * continues — so the ViewModel auto-expires a stale "typing" if no refresh arrives.
 */
data class TypingEvent(val chatGuid: String, val typing: Boolean)
