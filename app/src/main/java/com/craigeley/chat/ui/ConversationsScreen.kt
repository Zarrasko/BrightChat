@file:OptIn(ExperimentalFoundationApi::class)

package com.craigeley.chat.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craigeley.chat.ChatViewModel
import com.craigeley.chat.Conversation
import com.craigeley.chat.Status
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType

/** The conversation list — newest activity first, tap to open, tap title for settings. */
@Composable
fun ConversationsScreen(viewModel: ChatViewModel, onOpenSettings: () -> Unit, onNewMessage: () -> Unit) {
    val state by viewModel.state.collectAsState()

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
                text = "Messages",
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
            state.conversations.isEmpty() -> {
                val label = when {
                    state.status == Status.Loading -> "Loading…"
                    else -> state.message ?: "No conversations"
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
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.conversations, key = { it.guid }) { convo ->
                    // A tapback as the newest activity shows as "Liz loved an image";
                    // otherwise the real message text, prefixed "You: " when it's ours.
                    val subtitle = convo.lastReaction?.summary(state.contacts)
                        ?: ((if (convo.lastFromMe) "You: " else "") + convo.lastText)
                    ConversationRow(convo, state.contacts.title(convo), subtitle) { viewModel.open(convo) }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(convo: Conversation, title: String, subtitle: String, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            )
            .padding(vertical = 14.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = ChatType.body,
                color = ChatColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(text = relTime(convo.lastDate), style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        }
        if (subtitle.isNotEmpty()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = ChatType.meta,
                color = ChatColors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Relative timestamp ("3:14 PM", "Yesterday", "Mon") for the list. */
private fun relTime(ts: Long): String =
    if (ts <= 0L) "" else DateUtils.getRelativeTimeSpanString(
        ts,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
