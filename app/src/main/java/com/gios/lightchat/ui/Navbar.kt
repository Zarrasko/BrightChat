package com.gios.lightchat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.gios.lightchat.Contacts
import com.gios.lightchat.Conversation
import com.gios.lightchat.ui.theme.ChatColors

/**
 * The conversation list's tabs. [title] is what the header shows; Known is
 * titled "Messages" because it's the default view and the app's own name for
 * itself — renaming it to "Known" would make the common case read like a filter.
 *
 * There is no separate Unknown tab: everything that isn't starred lands in Messages
 * regardless of whether the sender is in the address book. The known/unknown
 * distinction still exists — [Store.notifyUnknown] uses [Contacts.knows] to decide
 * whether a stranger's message is allowed to buzz and raise the on-screen box — it's
 * just not surfaced as a second list to check.
 */
enum class ConversationTab(val title: String) {
    Favorites("Favorites"),
    Known("Messages"),

    /**
     * The dialer, which is also the contacts list.
     *
     * In the same enum as the three message tabs because it is the same bottom bar and the same
     * "which page am I on" question — not because it holds conversations. [tabOf] never returns
     * it, which is the honest expression of that: no conversation belongs here.
     */
    Dial("Dial"),
}

/**
 * Icons-only bottom bar, the same pattern as LightFog's navbar: Material glyphs at
 * 48dp, no labels, active white and inactive mid-grey, evenly spread with the
 * list's own 20dp gutter so the outer two icons line up with the rows above them.
 *
 * The glyphs are hand-parsed Material paths rather than a material-icons
 * dependency, matching how [TapbackGlyph] draws its heart and thumbs — the
 * artifact would be several megabytes for three shapes.
 *
 * A tab with anything unread carries a small filled dot at its top-right, the same
 * "something is pending here" language as the `• ` marker on an unread row.
 */
@Composable
fun ConversationNavbar(
    current: ConversationTab,
    unread: Set<ConversationTab>,
    onSelect: (ConversationTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in ConversationTab.entries) {
            TabIcon(
                tab = tab,
                active = tab == current,
                unread = tab in unread,
                onClick = { onSelect(tab) },
            )
        }
    }
}

@Composable
private fun TabIcon(tab: ConversationTab, active: Boolean, unread: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        ),
    ) {
        Icon(
            imageVector = tab.glyph,
            contentDescription = tab.title,
            tint = if (active) ChatColors.onSurface else ChatColors.onSurfaceInactive,
            modifier = Modifier.size(ICON_DP.dp),
        )
        if (unread) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // Out past the glyph's own transparent margin so it reads as a
                    // badge rather than part of the shape.
                    .offset(x = 2.dp, y = 2.dp)
                    .size(7.dp)
                    .background(ChatColors.onSurface, CircleShape),
            )
        }
    }
}

/**
 * Deliberately smaller than LightFog's own `n(48)` navbar convention: on this screen the
 * bar sits right below the header's much smaller pencil/refresh glyphs (22dp), and at 48dp
 * the size gap between "primary nav" and "corner utility" read as inconsistent rather than
 * as a hierarchy — especially at a premium on a 3.92" panel. 32dp still reads clearly as
 * the main navigation without dominating the row.
 */
private const val ICON_DP = 32

private val ConversationTab.glyph: ImageVector
    get() = when (this) {
        ConversationTab.Favorites -> StarVector
        ConversationTab.Known -> PersonVector
        ConversationTab.Dial -> PhoneVector
    }

// Material `star` and `person`, hand-parsed like the rest of this bar.
private val StarVector: ImageVector by lazy {
    vector("M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z")
}
private val PersonVector: ImageVector by lazy {
    vector("M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z")
}

/** 24dp-viewport path → tintable vector. The baked fill is irrelevant; `Icon` re-tints. */
/** Material's `phone`, hand-parsed like the three beside it. */
private val PhoneVector: ImageVector = vector(
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 " +
        "1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 " +
        "0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z",
)

private fun vector(pathData: String): ImageVector =
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(Color.White),
    ).build()

/**
 * Which tab a conversation belongs to. One function rather than two filters, so
 * the lists are exhaustive and disjoint by construction — a starred chat is in
 * Favorites and *only* Favorites, and nothing can appear twice or vanish.
 *
 * [contacts] is unused here — kept in the signature so this stays the one place that
 * decides tab membership even though known/unknown no longer splits the list; see
 * [Contacts.knows] for where that distinction still matters (notification gating).
 */
@Suppress("UNUSED_PARAMETER")
fun tabOf(conversation: Conversation, contacts: Contacts, favorites: Set<String>): ConversationTab =
    if (conversation.guid in favorites) ConversationTab.Favorites else ConversationTab.Known
