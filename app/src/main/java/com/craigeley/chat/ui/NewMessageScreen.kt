package com.craigeley.chat.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
 * number, or email) or enter a raw address, then tap to add a recipient. Each
 * chosen recipient becomes a removable chip; one recipient is a 1:1, two or more
 * a group (group sending is gated on the server's Private API — see the ViewModel).
 * Type the first message and send; that creates the chat (`chat/new`) and drops
 * into the thread. The photo picker is offered for 1:1 only (the group create path
 * can't take a constructed guid for the attachment).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewMessageScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var recipients by remember { mutableStateOf<List<Contact>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun addRecipient(contact: Contact) {
        if (recipients.none { it.address.equals(contact.address, ignoreCase = true) }) {
            recipients = recipients + contact
        }
        query = ""
        runCatching { focus.requestFocus() }
    }

    // Hoisted to the top level (not a conditional branch) so the launcher isn't
    // created conditionally; the lambda reads the current recipients and only
    // sends when it's a 1:1 (the group create path can't take a constructed guid).
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val only = recipients.singleOrNull()
        if (uri != null && only != null) viewModel.sendNewImage(only.address, uri)
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
                onClick = viewModel::cancelNewMessage,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "New Message", style = ChatType.body, color = ChatColors.onSurfaceVariant)
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(24.dp))
        }

        // "To" line: chosen recipients as removable chips, then an inline field to
        // add more. FlowRow lets chips wrap and the field flow after them, native-style.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "To", style = ChatType.body, color = ChatColors.onSurfaceDim)
            Spacer(modifier = Modifier.width(16.dp))
            FlowRow(modifier = Modifier.weight(1f)) {
                recipients.forEach { contact ->
                    HapticText(
                        text = "${contact.name} ×",
                        style = ChatType.body,
                        color = ChatColors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(end = 12.dp),
                        onClick = {
                            recipients = recipients.filterNot {
                                it.address.equals(contact.address, ignoreCase = true)
                            }
                        },
                    )
                }
                Box(modifier = Modifier.widthIn(min = 120.dp)) {
                    if (query.isEmpty() && recipients.isEmpty()) {
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
        }
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        val q = query.trim()
        if (q.isNotEmpty()) {
            val matches = remember(q, state.contactList) {
                state.contactList
                    .filter { it.name.contains(q, true) || it.address.contains(q, true) }
                    .take(40)
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
                            onClick = { addRecipient(contact) },
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
                // Let the user add a raw number/email they typed.
                if (q.length >= 3 && matches.none { it.address.equals(q, ignoreCase = true) }) {
                    item {
                        HapticText(
                            text = "Add “$q”",
                            style = ChatType.body,
                            color = ChatColors.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            onClick = { addRecipient(Contact(q, q)) },
                        )
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        if (recipients.isNotEmpty()) {
            ComposeBar(
                onSend = { viewModel.sendNewMessage(recipients.map { it.address }, it) },
                onPickImage = if (recipients.size == 1) {
                    { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                } else {
                    null
                },
            )
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
