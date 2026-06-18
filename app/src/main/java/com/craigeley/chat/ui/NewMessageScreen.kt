package com.craigeley.chat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craigeley.chat.ChatViewModel
import com.craigeley.chat.Contact
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType

/**
 * Start a new conversation: type into "To" to search the address book (by name,
 * number, or email) or enter a raw address, pick a recipient, then type the first
 * message. Sending creates the chat (`chat/new`) and drops into the thread.
 */
@Composable
fun NewMessageScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var recipient by remember { mutableStateOf<Contact?>(null) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HapticText(
                text = "‹",
                style = ChatType.title,
                color = ChatColors.onSurface,
                onClick = viewModel::cancelNewMessage,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "New Message", style = ChatType.body, color = ChatColors.onSurfaceVariant)
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(24.dp))
        }

        if (recipient == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "To", style = ChatType.body, color = ChatColors.onSurfaceDim)
                Spacer(modifier = Modifier.width(16.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Name, number, or email",
                            style = ChatType.body,
                            color = ChatColors.onSurfaceDisabled,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                        cursorBrush = SolidColor(ChatColors.onSurface),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
            HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

            val q = query.trim()
            val matches = remember(q, state.contactList) {
                if (q.isEmpty()) {
                    emptyList()
                } else {
                    state.contactList
                        .filter { it.name.contains(q, true) || it.address.contains(q, true) }
                        .take(40)
                }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(matches, key = { it.address }) { contact ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        HapticText(
                            text = contact.name,
                            style = ChatType.body,
                            color = ChatColors.onSurface,
                            textAlign = TextAlign.Start,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { recipient = contact },
                        )
                        Text(
                            text = contact.address,
                            style = ChatType.hint,
                            color = ChatColors.onSurfaceDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Let the user send to a raw number/email they typed.
                if (q.length >= 3 && matches.none { it.address.equals(q, ignoreCase = true) }) {
                    item {
                        HapticText(
                            text = "Send to “$q”",
                            style = ChatType.body,
                            color = ChatColors.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            onClick = { recipient = Contact(q, q) },
                        )
                    }
                }
            }
        } else {
            val r = recipient!!
            HapticText(
                text = "To: ${r.name}",
                style = ChatType.body,
                color = ChatColors.onSurface,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                onClick = { recipient = null }, // tap to pick someone else
            )
            HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
            Spacer(modifier = Modifier.weight(1f))
            ComposeBar(onSend = { viewModel.sendNewMessage(r.address, it) })
        }

        state.message?.let {
            Text(
                text = it,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}
