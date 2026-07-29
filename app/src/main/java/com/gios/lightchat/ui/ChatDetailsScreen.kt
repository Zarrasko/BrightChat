package com.gios.lightchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType

/**
 * Details for the open group (tap the thread title): its name, its members, and —
 * when the server's Private API is live — the management verbs: rename, add,
 * remove, leave. Without the Private API it's a read-only member list (the server
 * gates all four operations on it). Destructive taps (Remove / Leave) confirm by
 * a second tap rather than a dialog, keeping the LightOS text-only style.
 */
@Composable
fun ChatDetailsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val convo = state.open ?: return
    val canManage = state.privateApi

    var editingName by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf(convo.displayName) }
    var addingTo by remember { mutableStateOf(false) }
    var addQuery by remember { mutableStateOf("") }
    // The address whose Remove (or "leave") is one tap from firing; any other
    // tap resets it, so a stray touch can't remove someone.
    var confirming by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "Details",
            onBack = onBack,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text(text = "Name", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        Spacer(modifier = Modifier.height(4.dp))
        if (editingName && canManage) {
            Column(modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = draftName,
                    onValueChange = { draftName = it },
                    singleLine = true,
                    textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                    cursorBrush = SolidColor(ChatColors.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (draftName.isNotBlank()) viewModel.renameGroup(draftName)
                        editingName = false
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
            }
        } else {
            val name = convo.displayName.ifBlank { "No name" }
            if (canManage) {
                HapticText(
                    text = name,
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        draftName = convo.displayName
                        editingName = true
                        confirming = null
                    },
                )
            } else {
                Text(text = name, style = ChatType.body, color = ChatColors.onSurface, maxLines = 1)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "People", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        Spacer(modifier = Modifier.height(4.dp))
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(convo.participants, key = { it }) { address ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.contacts.name(address) ?: address,
                            style = ChatType.body,
                            color = ChatColors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (state.contacts.name(address) != null) {
                            Text(
                                text = address,
                                style = ChatType.hint,
                                color = ChatColors.onSurfaceDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (canManage && convo.participants.size > 2) {
                        Spacer(modifier = Modifier.width(12.dp))
                        HapticText(
                            text = if (confirming == address) "Remove?" else "Remove",
                            style = ChatType.hint,
                            color = if (confirming == address) ChatColors.onSurface else ChatColors.onSurfaceDim,
                            onClick = {
                                if (confirming == address) {
                                    confirming = null
                                    viewModel.removeMember(address)
                                } else {
                                    confirming = address
                                }
                            },
                        )
                    }
                }
            }
            if (canManage) {
                item {
                    if (addingTo) {
                        AddMemberField(
                            query = addQuery,
                            onQueryChange = { addQuery = it },
                            state = state,
                            onPick = { address ->
                                viewModel.addMember(address)
                                addingTo = false
                                addQuery = ""
                            },
                        )
                    } else {
                        HapticText(
                            text = "Add someone",
                            style = ChatType.body,
                            color = ChatColors.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            onClick = {
                                addingTo = true
                                confirming = null
                            },
                        )
                    }
                }
            }
        }

        if (canManage) {
            HapticText(
                text = if (confirming == LEAVE) "Leave the conversation?" else "Leave conversation",
                style = ChatType.body,
                color = if (confirming == LEAVE) ChatColors.onSurface else ChatColors.onSurfaceDim,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                onClick = {
                    if (confirming == LEAVE) {
                        confirming = null
                        viewModel.leaveGroup() // closes the thread; details unmount with it
                    } else {
                        confirming = LEAVE
                    }
                },
            )
        }

        state.message?.let {
            Text(
                text = it,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

/** Sentinel for [ChatDetailsScreen]'s two-tap confirm on Leave (it shares the
 *  `confirming` slot with the per-member Remove, which stores addresses). */
// Escaped rather than a literal NUL byte: an actual 0x00 in the source made
// git and grep treat this whole file as binary, so it never showed in a diff.
private const val LEAVE = "\u0000leave"

/** Inline add-member picker: type a name/number/email, tap a contact match (or
 *  the raw address) to add them to the group. */
@Composable
private fun AddMemberField(
    query: String,
    onQueryChange: (String) -> Unit,
    state: com.gios.lightchat.UiState,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = ChatType.body.copy(color = ChatColors.onSurface),
            cursorBrush = SolidColor(ChatColors.onSurface),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        val q = query.trim()
        if (q.isNotEmpty()) {
            val matches = state.contactList
                .filter { it.name.contains(q, true) || it.address.contains(q, true) }
                .take(5)
            matches.forEach { contact ->
                HapticText(
                    text = "${contact.name} — ${contact.address}",
                    style = ChatType.meta,
                    color = ChatColors.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    onClick = { onPick(contact.address) },
                )
            }
            if (q.length >= 3 && matches.none { it.address.equals(q, ignoreCase = true) }) {
                HapticText(
                    text = "Add “$q”",
                    style = ChatType.meta,
                    color = ChatColors.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    onClick = { onPick(q) },
                )
            }
        }
    }
}
