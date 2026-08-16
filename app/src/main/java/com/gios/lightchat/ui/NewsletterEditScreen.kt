package com.gios.lightchat.ui

import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.NewsletterBatch
import com.gios.lightchat.NewsletterTarget
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType

/**
 * Naming a batch and choosing who is in it.
 *
 * Two sources feed one search field: the conversation list (which is where **group** chats can
 * only come from — a group has no handle, so it can only be addressed by the guid of a room that
 * already exists) and the address book (which is where a person this phone has never messaged
 * can come from, since a 1:1 guid can be constructed from a handle). They are searched together
 * and labelled `Chat` / `Contact` so a group and a person of the same name are distinguishable
 * before you add one.
 *
 * Everything is held locally and written on Save, so backing out of a half-edited batch leaves
 * the stored one alone — the same shape as the agent editor.
 */
@Composable
fun NewsletterEditScreen(viewModel: ChatViewModel, batch: NewsletterBatch) {
    val state by viewModel.state.collectAsState()

    var name by remember(batch.id) { mutableStateOf(batch.name) }
    var targets by remember(batch.id) { mutableStateOf(batch.targets) }
    var query by remember(batch.id) { mutableStateOf("") }
    // Delete is destructive and there is no undo, so it confirms by a second tap — the same
    // pattern the group-details screen uses for leaving a chat.
    var confirmingDelete by remember(batch.id) { mutableStateOf(false) }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    fun add(target: NewsletterTarget) {
        if (targets.none { it.key == target.key }) targets = targets + target
        query = ""
    }

    val q = query.trim()

    // Candidates are recomputed only when the query or a source list changes — the search runs
    // over every conversation *and* every contact, which on this account is thousands of rows,
    // and doing it per recomposition would stutter the field it is being typed into.
    val chatMatches = remember(q, state.conversations, state.contacts, targets) {
        if (q.isEmpty()) {
            emptyList()
        } else {
            state.conversations
                .asSequence()
                // An agent is a synthetic local row, not a chat on the server — there is
                // nothing to broadcast to.
                .filterNot { it.isAgent }
                .map { NewsletterTarget(id = it.guid, label = state.contacts.title(it), isChat = true) }
                .filter { it.label.contains(q, true) }
                .filter { t -> targets.none { it.key == t.key } }
                // A LazyColumn throws on a repeated key, so the de-duplication is load-bearing
                // rather than tidiness — see the contact list below, where it is reachable.
                .distinctBy { it.key }
                .take(20)
                .toList()
        }
    }
    val contactMatches = remember(q, state.contactList, targets) {
        if (q.isEmpty()) {
            emptyList()
        } else {
            state.contactList
                .asSequence()
                .filter { it.name.contains(q, true) || it.address.contains(q, true) }
                .map { NewsletterTarget(id = it.address, label = it.name, isChat = false) }
                .filter { t -> targets.none { it.key == t.key } }
                // **Not optional.** `contactList` is the phone's address book merged with the
                // server's, de-duplicated only per contact card and only case-sensitively, so
                // two cards holding one number ("Mom" and "Home"), or `Jane@Example.com` beside
                // `jane@example.com`, both reach here as separate rows that collapse onto one
                // `key` — and a LazyColumn with a repeated key throws mid-composition. Typing
                // in this field crashed the app without it.
                .distinctBy { it.key }
                .take(20)
                .toList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "Batch",
            onBack = viewModel::closeNewsletterEditor,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            trailing = {
                HapticText(
                    text = "Save",
                    style = ChatType.hint,
                    color = if (targets.isEmpty()) ChatColors.onSurfaceDisabled else ChatColors.onSurface,
                    onClick = {
                        if (targets.isNotEmpty()) {
                            viewModel.saveNewsletterBatch(
                                batch.copy(name = name.trim(), targets = targets),
                            )
                        }
                    },
                )
            },
        )

        // Name line.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Name", style = ChatType.body, color = ChatColors.onSurfaceDim)
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (name.isEmpty()) {
                    Text(
                        text = NewsletterBatch.UNTITLED,
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDisabled,
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                    cursorBrush = SolidColor(ChatColors.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        // Add line.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Add", style = ChatType.body, color = ChatColors.onSurfaceDim)
            Spacer(modifier = Modifier.width(16.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Chat, name, or number",
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
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (q.isNotEmpty()) {
                // Searching: the two candidate lists replace the membership list. Chats first
                // — a batch is usually assembled out of threads that already exist, and a
                // person who has a thread is reachable either way.
                items(chatMatches.size, key = { "chat-" + chatMatches[it].key }) { i ->
                    CandidateRow(target = chatMatches[i], kind = "Chat", onAdd = { add(chatMatches[i]) })
                }
                items(contactMatches.size, key = { "contact-" + contactMatches[it].key }) { i ->
                    CandidateRow(target = contactMatches[i], kind = "Contact", onAdd = { add(contactMatches[i]) })
                }
                // A raw number or email nobody in the address book owns. Same escape hatch the
                // new-message screen offers, and for the same reason: the address book is the
                // Mac's, and it is not the only place a number can come from.
                if (q.length >= 3 &&
                    contactMatches.none { it.id.equals(q, ignoreCase = true) } &&
                    targets.none { !it.isChat && it.id.equals(q, ignoreCase = true) }
                ) {
                    item(key = "raw") {
                        HapticText(
                            text = "Add “$q”",
                            style = ChatType.body,
                            color = ChatColors.onSurfaceVariant,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            onClick = { add(NewsletterTarget(id = q, label = q, isChat = false)) },
                        )
                    }
                }
                if (chatMatches.isEmpty() && contactMatches.isEmpty() && q.length < 3) {
                    item(key = "nomatch") {
                        Text(
                            text = "No matches",
                            style = ChatType.hint,
                            color = ChatColors.onSurfaceDisabled,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                    }
                }
            } else if (targets.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "No recipients yet.\nSearch above to add chats and contacts.",
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDisabled,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            } else {
                // Membership. Tapping a row removes it — the row disappearing is the
                // confirmation, and putting it back is one search away.
                items(targets.size, key = { targets[it].key }) { i ->
                    val target = targets[i]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = target.label,
                                style = ChatType.body,
                                color = ChatColors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (target.isChat) "Chat" else "Contact · ${target.id}",
                                style = ChatType.hint,
                                color = ChatColors.onSurfaceDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        HapticText(
                            text = "×",
                            style = ChatType.body,
                            color = ChatColors.onSurfaceVariant,
                            onClick = { targets = targets.filterNot { it.key == target.key } },
                        )
                    }
                }
            }
        }

        // Deleting only makes sense for a batch that has been saved; a brand-new one is
        // discarded by backing out.
        if (state.newsletters.any { it.id == batch.id }) {
            HapticText(
                text = if (confirmingDelete) "Delete batch?" else "Delete batch",
                style = ChatType.hint,
                color = if (confirmingDelete) ChatColors.onSurface else ChatColors.onSurfaceDim,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                onClick = {
                    if (confirmingDelete) {
                        viewModel.deleteNewsletterBatch(batch.id)
                    } else {
                        confirmingDelete = true
                    }
                },
            )
        }
    }
}

@Composable
private fun CandidateRow(target: NewsletterTarget, kind: String, onAdd: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        HapticText(
            text = target.label,
            style = ChatType.body,
            color = ChatColors.onSurface,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
            onClick = onAdd,
        )
        Text(
            text = if (kind == "Chat") kind else "$kind · ${target.id}",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
