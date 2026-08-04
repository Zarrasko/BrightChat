@file:OptIn(ExperimentalFoundationApi::class)

package com.gios.lightchat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ChatViewModel
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.Conversation
import com.gios.lightchat.Pins
import com.gios.lightchat.Status
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The conversation list — newest activity first, tap to open, long-press to star,
 * tap the title for settings. Split across three tabs (see [ConversationTab]) with
 * a LightFog-style icon bar at the bottom.
 *
 * [tab] and [listState] are hoisted into `LightChatApp` rather than remembered here:
 * this composable leaves the composition entirely while a thread is open, so a
 * local scroll position would be discarded and every exit would land back at the
 * top of the list.
 */
@Composable
fun ConversationsScreen(
    viewModel: ChatViewModel,
    tab: ConversationTab,
    listState: LazyListState,
    onSelectTab: (ConversationTab) -> Unit,
    onOpenSettings: () -> Unit,
    onNewMessage: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    // The wheel scrolls whichever tab is showing. The state is the one hoisted in
    // LightChatApp, so a notch moves the list you can see and not one of its siblings.
    WheelScroll(listState)

    // Partitioned once per list/contacts/favorites change, not per row.
    val visible = remember(state.conversations, state.contacts, state.favorites, state.pins, tab) {
        val onTab = state.conversations.filter { tabOf(it, state.contacts, state.favorites) == tab }
        // Pins only mean anything on Favorites. A pin is an order *within* the starred list, and
        // applying it to Messages would move a chat above conversations that are simply newer —
        // which is the one thing that list promises not to do.
        if (tab == ConversationTab.Favorites) Pins.order(onTab, state.pins) else onTab
    }
    val unreadTabs = remember(state.conversations, state.contacts, state.favorites) {
        state.conversations
            .filter { it.unread }
            .mapTo(mutableSetOf()) { tabOf(it, state.contacts, state.favorites) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HapticText(
                text = "New",
                style = ChatType.hint,
                color = ChatColors.onSurfaceVariant,
                onClick = onNewMessage,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.Start,
            )
            Spacer(modifier = Modifier.weight(1f))
            HapticText(
                text = tab.title,
                style = ChatType.body,
                color = ChatColors.onSurface,
                onClick = onOpenSettings,
            )
            Spacer(modifier = Modifier.weight(1f))
            HapticText(
                text = "Refresh",
                style = ChatType.hint,
                color = if (state.status == Status.Loading) ChatColors.onSurfaceDisabled else ChatColors.onSurfaceVariant,
                onClick = viewModel::refresh,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
            )
        }

        when {
            visible.isEmpty() -> {
                val label = when {
                    // Only the first load is worth a spinner-ish line; once the list
                    // has arrived an empty tab is a fact about the tab, not a state.
                    state.conversations.isEmpty() && state.status == Status.Loading -> "Loading…"
                    state.conversations.isEmpty() -> state.message ?: "No conversations"
                    tab == ConversationTab.Favorites -> "No favorites yet.\nLong-press a chat to star it."
                    tab == ConversationTab.Unknown -> "No unknown senders"
                    else -> "No conversations"
                }
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDisabled,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(visible, key = { it.guid }) { convo ->
                    // A tapback as the newest activity shows as "Liz loved an image";
                    // otherwise the real message text, prefixed "You: " when it's ours.
                    val subtitle = convo.lastReaction?.summary(state.contacts)
                        ?: ((if (convo.lastFromMe) "You: " else "") + convo.lastText)
                    ConversationRow(
                        convo = convo,
                        title = state.contacts.title(convo),
                        subtitle = subtitle,
                        // Deleting a chat needs the Private API (server gate); only then
                        // do we let the row swipe to reveal Delete.
                        canDelete = state.privateApi,
                        onDelete = { viewModel.deleteConversation(convo) },
                        onClick = { viewModel.open(convo) },
                        onToggleFavorite = { viewModel.toggleFavorite(convo) },
                        // Only on Favorites, and only there because that is the only list a pin
                        // has an opinion about. Its own small verb rather than another gesture:
                        // the row already spends its long-press on starring and its swipe on
                        // Delete, and a third would be one too many to remember.
                        pinned = if (tab == ConversationTab.Favorites) {
                            Pins.isPinned(state.pins, convo.guid)
                        } else {
                            null
                        },
                        onTogglePin = { viewModel.togglePin(convo) },
                    )
                }
            }
        }

        ConversationNavbar(
            current = tab,
            unread = unreadTabs,
            onSelect = onSelectTab,
            // MainActivity hides the system bars, so this is usually zero — but it
            // keeps the glyphs off the gesture strip on the swipe that brings the
            // bars back transiently.
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
private fun ConversationRow(
    convo: Conversation,
    title: String,
    subtitle: String,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    /** Whether this row is pinned, or null on a tab where pinning means nothing. */
    pinned: Boolean? = null,
    onTogglePin: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val scope = rememberCoroutineScope()
    // How far the row slides left to reveal Delete. Keyed to the guid so a recycled
    // row for a different chat starts closed.
    val revealPx = with(LocalDensity.current) { 96.dp.toPx() }
    val offsetX = remember(convo.guid) { Animatable(0f) }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Behind the row, uncovered as it slides left. Tapping acts immediately — the swipe is
        // its own confirm.
        //
        // **On Favorites this is Unstar, not Delete.** The long-press on that tab now pins, so
        // unstarring needs somewhere to live, and it belongs here: it is the destructive-ish verb
        // and this is where the destructive verb goes. Deleting a chat you starred is rarer than
        // unstarring one, and Delete is still one tab away on Messages.
        val revealable = pinned != null || canDelete
        if (revealable) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
                HapticText(
                    text = if (pinned != null) "Unstar" else "Delete",
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    onClick = if (pinned != null) onToggleFavorite else onDelete,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                // Opaque so the Delete action stays hidden under the row until swiped.
                .background(ChatColors.background)
                .then(
                    if (revealable) {
                        Modifier.pointerInput(convo.guid) {
                            detectHorizontalDragGestures(
                                onHorizontalDrag = { change, drag ->
                                    change.consume()
                                    scope.launch {
                                        offsetX.snapTo((offsetX.value + drag).coerceIn(-revealPx, 0f))
                                    }
                                },
                                // Settle open past the halfway point, else snap closed.
                                onDragEnd = {
                                    val target = if (offsetX.value < -revealPx / 2f) -revealPx else 0f
                                    scope.launch { offsetX.animateTo(target) }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                )
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // While open, a tap just closes the row rather than opening it.
                        if (offsetX.value < -1f) scope.launch { offsetX.animateTo(0f) } else onClick()
                    },
                    /**
                     * **Long-press means the useful verb for the list you are on.**
                     *
                     * Everywhere else that is star / unstar, and the row moving to another tab is
                     * its own confirmation. On Favorites the chat is already starred, so starring
                     * is the one thing it cannot do — there the press pins instead, and the "↑"
                     * appearing on the title is the confirmation.
                     *
                     * The first attempt gave pinning its own text verb in the row. It shared the
                     * line with the title and the timestamp on a 3.92" panel, so the title lost
                     * about a third of its width to a word that is only relevant on one tab —
                     * and a tap target that small sitting inside a row that is itself clickable
                     * and swipeable is a coin toss. A gesture the row already has costs nothing.
                     */
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        when {
                            offsetX.value < -1f -> scope.launch { offsetX.animateTo(0f) }
                            pinned != null -> onTogglePin()
                            else -> onToggleFavorite()
                        }
                    },
                )
                .padding(vertical = 14.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Text-only unread marker, in keeping with the B&W style; the
                    // brighter subtitle below reinforces it.
                    // Two markers, both text, both prefixes, because that is how this app says
                    // "something is true about this row" everywhere else. A pin is an anchor:
                    // "↑" reads as "held up here" without needing a legend.
                    text = (if (pinned == true) "↑ " else "") + (if (convo.unread) "• " else "") + title,
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = listTime(LocalContext.current, convo.lastDate),
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                )
            }
            if (subtitle.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = ChatType.meta,
                    color = if (convo.unread) ChatColors.onSurface else ChatColors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

