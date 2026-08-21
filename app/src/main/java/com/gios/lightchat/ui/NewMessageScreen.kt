package com.gios.lightchat.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Contact
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType

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
    // Recipients the server says can't receive iMessages (Private-API check; empty
    // when the check isn't available). Sending is blocked while any are present —
    // an iMessage to such an address *appears* to send and dies silently on the Mac.
    var unavailable by remember { mutableStateOf<Set<String>>(emptySet()) }
    val focus = remember { FocusRequester() }
    // Speaking a first message rather than typing it. See [rememberDictation].
    val dictate = rememberDictation(viewModel)

    // Our own picker (see PhotoPickerScreen), shown over this screen. Only a 1:1 can
    // take one: the group create path can't send to a constructed guid. Just the first
    // photo — the new-message flow makes one chat with one message, and the rest of a
    // multi-pick would have nowhere to go until that chat exists. Saveable because
    // handing off to the camera app can take the process with it.
    var picking by rememberSaveable { mutableStateOf(false) }

    // Keyed on picking, not Unit: the "To" field is disposed while the picker is up
    // (it lives below the early return), so it comes back unfocused and the effect has
    // to run again to put the keyboard back.
    LaunchedEffect(picking) { if (!picking) runCatching { focus.requestFocus() } }

    fun addRecipient(contact: Contact) {
        if (recipients.none { it.address.equals(contact.address, ignoreCase = true) }) {
            recipients = recipients + contact
            viewModel.checkIMessage(contact.address) { ok ->
                if (!ok) unavailable = unavailable + contact.address
            }
        }
        query = ""
        runCatching { focus.requestFocus() }
    }


    // Early return rather than an overlay: unlike the thread there's no scroll
    // position to protect, and everything remembered above this line keeps its slot,
    // so the recipients you'd already chosen are still there when it closes.
    if (picking) {
        BackHandler { picking = false }
        PhotoPickerScreen(
            onSend = { files ->
                picking = false
                val only = recipients.singleOrNull()
                val first = files.firstOrNull()
                if (only != null && first != null) viewModel.sendNewImage(only.address, first)
            },
            onClose = { picking = false },
            // A new 1:1 can open on a clip; sendNewImage streams it rather than
            // reading it into memory the way the still path does.
            allowVideo = true,
        )
        return
    }

    // Contact search can return forty rows on a phone that shows six, and the keyboard is
    // up the whole time this screen exists — which is precisely the case the wheel is for,
    // since it doesn't need the hand that's typing. Below the early return, so nothing here
    // competes with the picker's grid.
    val matchList = rememberLazyListState()
    WheelScroll(matchList)

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "New Message",
            onBack = viewModel::cancelNewMessage,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        // An agent is not a person in the address book — a separate creation path.
        HapticText(
            text = "New agent",
            style = ChatType.body,
            color = ChatColors.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            onClick = { viewModel.openNewAgent() },
        )
        // Nor is a newsletter batch: it addresses many threads at once rather than opening one,
        // so it cannot be a recipient in the "To" field below — it is its own destination.
        HapticText(
            text = "Newsletter",
            style = ChatType.body,
            color = ChatColors.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            onClick = { viewModel.openNewsletters() },
        )
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

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
                        // A can't-deliver recipient reads disabled; the line below
                        // the divider says why.
                        color = if (contact.address in unavailable) {
                            ChatColors.onSurfaceDisabled
                        } else {
                            ChatColors.onSurfaceVariant
                        },
                        maxLines = 1,
                        modifier = Modifier.padding(end = 12.dp),
                        onClick = {
                            recipients = recipients.filterNot {
                                it.address.equals(contact.address, ignoreCase = true)
                            }
                            unavailable = unavailable - contact.address
                        },
                    )
                }
                // Size to content (min width so it stays tappable) rather than
                // fillMaxWidth — inside a FlowRow the latter demands the whole row,
                // forcing the cursor onto its own line below the chips even when
                // there's room beside them.
                Box {
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
                        modifier = Modifier.widthIn(min = 120.dp).focusRequester(focus),
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        // Recipients the availability check flagged: name why sending is blocked.
        val blocked = recipients.filter { it.address in unavailable }
        if (blocked.isNotEmpty()) {
            Text(
                text = blocked.joinToString(", ") { it.name } +
                    (if (blocked.size == 1) " isn’t" else " aren’t") +
                    " on iMessage — the message can’t be delivered",
                style = ChatType.hint,
                color = ChatColors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        val canSend = recipients.isNotEmpty() && blocked.isEmpty()
        val sendNew: (String) -> Unit = { viewModel.sendNewMessage(recipients.map { it.address }, it) }
        val pickForCompose: (() -> Unit)? = if (recipients.size == 1) {
            { picking = true }
        } else {
            null
        }

        val q = query.trim()
        if (q.isNotEmpty()) {
            // Searching: contact matches fill the space; the compose bar (when a
            // recipient is already chosen) sits below them with its own divider.
            val matches = remember(q, state.contactList) {
                state.contactList
                    .filter { it.name.contains(q, true) || it.address.contains(q, true) }
                    .take(40)
            }
            LazyColumn(state = matchList, modifier = Modifier.weight(1f).fillMaxWidth()) {
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
            if (canSend) {
                ComposeBar(
                    onSend = sendNew,
                    onPickImage = pickForCompose,
                    onDictate = dictate.onTap.takeIf { dictate.available },
                    dictating = dictate.listening,
                )
            }
        } else if (canSend) {
            // Composing: the message field hugs the "To" divider (no gap, no second
            // line) so it's right under the recipient; the empty room falls below it.
            ComposeBar(
                onSend = sendNew,
                onPickImage = pickForCompose,
                showTopDivider = false,
                onDictate = dictate.onTap.takeIf { dictate.available },
                dictating = dictate.listening,
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
