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
    // Set when the newest activity is a tapback, so the list can show "Liz loved an
    // image" instead of the older real message ([lastText] still holds that fallback).
    // Cleared the moment a normal message arrives. See BlueBubblesApi.conversations.
    val lastReaction: ReactionPreview? = null,
    // Every chat-room guid this conversation spans. Usually just [guid]; for a group
    // that iMessage has forked into sibling rooms with the same name + participants,
    // it lists all of them ordered by newest *non-reaction* message, so [guid] is the
    // live room — the send target. (Newest message of any kind isn't safe: a tapback can
    // land in a dead old room, and AppleScript can't send there.) The thread
    // fetches/merges messages across all of them. See BlueBubblesApi.conversations.
    val guids: List<String> = listOf(guid),
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

    /** Past-tense verb for the conversation-list summary ("Liz loved an image"). */
    val verb: String
        get() = when (this) {
            LOVE -> "loved"
            LIKE -> "liked"
            DISLIKE -> "disliked"
            LAUGH -> "laughed at"
            EMPHASIZE -> "emphasized"
            QUESTION -> "questioned"
        }

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

/**
 * Conversation-list descriptor for a chat whose newest activity is a tapback, so
 * the row reads "Liz loved an image" the way iMessage does (rather than falling
 * back to the older real message). The reactor's name is resolved at render time
 * (via [Contacts]) — [reactor] is their raw handle, null when the tapback is mine.
 * [target] already describes what was reacted to ("an image", a quoted text, or
 * "a message" when the target isn't to hand). See [ChatMessage.reactionPreview].
 */
data class ReactionPreview(
    val type: ReactionType,
    val fromMe: Boolean,
    val reactor: String?,
    val target: String,
) {
    /** The one-line summary, resolving the reactor to a name ("You" when mine). */
    fun summary(contacts: Contacts): String {
        val who = if (fromMe) "You" else reactor?.let { contacts.sender(it) } ?: "Someone"
        return "$who ${type.verb} $target"
    }
}

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
    // The client tempGuid we sent this message with, echoed back by the server. Lets
    // the socket echo of our own send reconcile against the optimistic bubble (whose
    // guid IS the tempGuid) instead of rendering a second row. Null for messages we
    // didn't send / weren't sent with a tempGuid.
    val tempGuid: String? = null,
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

    /**
     * If this message is a tapback (and not a *removal*), a [ReactionPreview] for the
     * conversation list, resolving its target via [findTarget] to describe what was
     * reacted to ("an image" / a quoted text / "a message" when the target isn't
     * cached). Null for normal messages and reaction removals (those revert the list
     * to the underlying real message).
     */
    fun reactionPreview(findTarget: (String) -> ChatMessage?): ReactionPreview? {
        val type = reactionType ?: return null
        if (isReactionRemoval) return null
        val target = reactionTargetGuid?.let(findTarget)
        val desc = when {
            target == null -> "a message"
            target.images.isNotEmpty() -> "an image"
            target.attachments.isNotEmpty() -> "an attachment"
            target.text.isNotBlank() && target.text != ATTACHMENT_PLACEHOLDER -> "“${target.text.trim()}”"
            else -> "a message"
        }
        return ReactionPreview(type, fromMe, sender, desc)
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
