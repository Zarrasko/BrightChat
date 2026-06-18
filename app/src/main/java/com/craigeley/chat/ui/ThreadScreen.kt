package com.craigeley.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craigeley.chat.ChatMessage
import com.craigeley.chat.ChatViewModel
import com.craigeley.chat.Contacts
import com.craigeley.chat.Conversation
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType

/** One open conversation: messages oldest→newest, user on the right, others left. */
@Composable
fun ThreadScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val convo = state.open ?: return
    val listState = rememberLazyListState()

    // Which messages begin a same-speaker run (so only they get a name label).
    val labeled = remember(state.messages) {
        buildSet {
            state.messages.forEachIndexed { i, m ->
                val prev = state.messages.getOrNull(i - 1)
                if (prev == null || prev.fromMe != m.fromMe || prev.sender != m.sender) add(m.guid)
            }
        }
    }

    // The list is reverse-laid-out (newest pinned to the bottom), so opening a
    // thread shows the latest immediately — no scroll to watch. Only nudge to the
    // bottom for a *new* newest message, and only if the user is already down there
    // (don't yank them away while they're scrolled up reading history).
    val newestGuid = state.messages.lastOrNull()?.guid
    LaunchedEffect(newestGuid) {
        if (newestGuid != null && listState.firstVisibleItemIndex <= 2) {
            listState.animateScrollToItem(0)
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HapticText(
                text = "‹",
                style = ChatType.title,
                color = ChatColors.onSurface,
                onClick = viewModel::closeThread,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = state.contacts.title(convo),
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.weight(1f))
            // Balances the back chevron so the title sits centred.
            Spacer(modifier = Modifier.width(24.dp))
        }

        if (state.messages.isEmpty() && state.threadLoading) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(text = "Loading…", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                reverseLayout = true,
                contentPadding = PaddingValues(top = 8.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Newest first so reverseLayout pins it to the bottom.
                items(state.messages.asReversed(), key = { it.guid }) { message ->
                    MessageRow(message, convo, state.contacts, showLabel = message.guid in labeled)
                }
            }
        }

        ComposeBar(onSend = viewModel::sendMessage)
    }
}

/** Bottom compose row: a growing text field and a Send action. Shared with the
 *  new-message screen. */
@Composable
fun ComposeBar(onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (input.isEmpty()) {
                    Text(text = "Message", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                    cursorBrush = SolidColor(ChatColors.onSurface),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            HapticText(
                text = "Send",
                style = ChatType.body,
                color = if (input.isBlank()) ChatColors.onSurfaceDisabled else ChatColors.onSurface,
                onClick = {
                    if (input.isNotBlank()) {
                        onSend(input)
                        input = ""
                    }
                },
            )
        }
    }
}

/** Fraction of width a single turn may span. No bubbles signal who's talking, so
 *  capping the width leaves an empty gutter on the opposite side as the cue. */
private const val MESSAGE_MAX_WIDTH = 0.8f

/** A dim sender label (only when needed), then the text — no bubbles, just a
 *  width-capped column hugging its side. */
@Composable
private fun MessageRow(message: ChatMessage, convo: Conversation, contacts: Contacts, showLabel: Boolean) {
    val align = if (message.fromMe) Alignment.End else Alignment.Start
    val textAlign = if (message.fromMe) TextAlign.End else TextAlign.Start
    // Name labels on both sides — "You" for your turns, the sender's name for
    // incoming (falling back to the 1:1 counterpart when a message has no handle) —
    // but only on the first message of a same-speaker run.
    val label = when {
        !showLabel -> null
        message.fromMe -> "You"
        message.sender != null -> contacts.sender(message.sender)
        convo.participants.size == 1 -> contacts.sender(convo.participants[0])
        else -> null
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = align) {
        Column(
            modifier = Modifier.fillMaxWidth(MESSAGE_MAX_WIDTH),
            horizontalAlignment = align,
        ) {
            if (label != null) {
                Text(text = label, style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (message.text.isNotEmpty()) {
                Text(
                    text = message.text,
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    textAlign = textAlign,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
