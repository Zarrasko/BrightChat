package com.gios.lightchat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The contact page: everything a conversation has accumulated that isn't the conversation
 * itself. Photos and links are derived from the messages the phone holds; the note lives
 * in another app entirely (see [NotebookLink]).
 *
 * Held apart from `UiState` because it is expensive to build and nothing outside one
 * screen reads it: opening a thread should not spend a frame extracting every URL in it.
 */
data class DetailsState(
    /** The conversation this describes. Null before the page has opened. */
    val chatGuid: String? = null,
    /**
     * Whether any of the conversation's rooms has ever been fetched. False means the store
     * holds nothing for it — an empty grid then means "never opened", not "no photos", and
     * the two have to read differently or the screen looks broken.
     */
    val threadLoaded: Boolean = false,
    /** Every image held, newest first. */
    val images: List<Attachment> = emptyList(),
    /** Every URL sent, newest first, one row per distinct URL. */
    val links: List<SharedLink> = emptyList(),
    /** How many messages back the page is currently reading. Grows as it is scrolled. */
    val window: Int = 0,
    /** How many messages that window actually produced — the growth check in [DetailsState.exhausted]. */
    val scanned: Int = 0,
    val loading: Boolean = false,
    /** No more history to be had, so stop asking. */
    val exhausted: Boolean = false,
)

/** One URL somebody sent, and the message it came in. */
data class SharedLink(
    val url: String,
    val date: Long,
    /** The sender's handle address; null when it was me. */
    val sender: String?,
    val fromMe: Boolean,
)

/**
 * Every image in [messages], newest first.
 *
 * De-duplicated by attachment guid because a forked group's rooms can both hold the same
 * message, and because a window that has been grown re-reads rows it already had. The
 * de-dup keeps the first it sees, which after the sort is the newest carrier — so
 * reactions have to go first, exactly as in [linksIn]: a tapback echoes its target's
 * attachment on some server versions, and a photo from two years ago would sort to the top
 * of the grid because somebody liked it last week. Group events go with them, or a changed
 * group avatar turns up among the photographs.
 */
fun imagesIn(messages: List<ChatMessage>): List<Attachment> =
    messages.asSequence()
        .filterNot { it.isReaction || it.isGroupEvent }
        .sortedByDescending { it.date }
        .flatMap { it.images }
        .distinctBy { it.guid }
        .toList()

/**
 * Every URL in [messages], newest first, once each.
 *
 * The same matcher the thread makes links tappable with ([URL_REGEX]) — a second one here
 * would eventually disagree with it about what a link is, and the two are looking at the
 * same text. Re-sends of a link collapse onto the newest, which is the one worth showing:
 * "we talked about this yesterday", not two years ago.
 */
fun linksIn(messages: List<ChatMessage>): List<SharedLink> {
    val byUrl = LinkedHashMap<String, SharedLink>()
    for (message in messages.sortedByDescending { it.date }) {
        // A tapback carries the target's text on some server versions, which would list
        // the link a second time under whoever liked it.
        if (message.isReaction) continue
        for (match in URL_REGEX.findAll(message.text)) {
            byUrl.putIfAbsent(
                match.value,
                SharedLink(
                    url = match.value,
                    date = message.date,
                    sender = message.sender,
                    fromMe = message.fromMe,
                ),
            )
        }
    }
    return byUrl.values.toList()
}

/**
 * A URL shortened to the two informative parts — where it goes and what it points at —
 * with the middle removed. Elided from the middle rather than the end because the end is
 * usually the article title and the start is usually `https://www.`.
 */
fun elideUrl(url: String, max: Int = 46): String {
    val trimmed = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    if (trimmed.length <= max) return trimmed
    val head = (max - 1) / 2
    val tail = max - 1 - head
    return trimmed.take(head) + "…" + trimmed.takeLast(tail)
}

/**
 * The conversation's note, which lives in `gi-os/LightNotebook`.
 *
 * Nothing is stored here and nothing is read back: the link opens the note, and creates it
 * on the far side if this is the first tap. LightNotebook is not a dependency in any sense
 * — if it isn't installed the row says so and does nothing.
 *
 * **The key is the conversation's normalised handles, never its guid.** A chat guid is
 * local to one Mac's `chat.db`; restoring from a backup, or moving to another Mac, would
 * strand every note ever written. Handles survive that, and they are what the phone would
 * use to find the same conversation again.
 */
object NotebookLink {

    const val PACKAGE = "com.gios.lightnotebook"

    private const val SCHEME = "lightnotebook"

    fun uri(key: String, title: String): Uri =
        Uri.parse("$SCHEME://note/${Uri.encode(key)}?title=${Uri.encode(title)}")

    /**
     * Whether anything on the phone answers the link.
     *
     * Asked rather than inferred from a failed launch: `startActivity` throwing is how you
     * find out *after* the user has tapped, and this decides what the row says before they
     * do. Needs the `<queries>` block in the manifest — without it Android 11's package
     * filtering hides LightNotebook and this is always false.
     */
    fun available(context: Context): Boolean = runCatching {
        context.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_VIEW, uri("probe", "")),
                // MATCH_DEFAULT_ONLY, not 0: an implicit `startActivity` only ever picks a
                // filter carrying CATEGORY_DEFAULT, so a plain query is more generous than
                // the launch it is predicting — and the row would offer to open a note
                // that then refuses to open.
                PackageManager.MATCH_DEFAULT_ONLY,
            )
            .isNotEmpty()
    }.getOrDefault(false)

    /** Opens (creating if needed) the note for [key]. False if nothing took the intent. */
    fun open(context: Context, key: String, title: String): Boolean = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri(key, title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)
}
