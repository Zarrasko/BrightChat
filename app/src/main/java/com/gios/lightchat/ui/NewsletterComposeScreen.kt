package com.gios.lightchat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.NewsletterBatch
import com.gios.lightchat.SendState
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File

/**
 * Writing the one message a batch goes out to.
 *
 * Deliberately not a thread: there is no conversation to show, because the message is about to
 * become N conversations. What the screen owes the user instead is **who is about to receive
 * this** — the recipient list is the body of the screen, not a count in a header — since a
 * broadcast is the one send in this app that cannot be taken back and can go to the wrong forty
 * people as easily as the right ones.
 *
 * Photos are *attached* here rather than fired on pick (the thread's behaviour), for the same
 * reason: picking a photo in a thread sends it to one person, and picking one here would send it
 * to everybody the instant it was tapped. They are listed, removable, and go out with the words.
 */
@Composable
fun NewsletterComposeScreen(viewModel: ChatViewModel, batch: NewsletterBatch) {
    val state by viewModel.state.collectAsState()

    // Saveable: handing off to the camera inside the picker can take the process with it.
    var picking by rememberSaveable { mutableStateOf(false) }
    var attached by remember(batch.id) { mutableStateOf<List<File>>(emptyList()) }
    var input by rememberSaveable(batch.id) { mutableStateOf("") }
    // Armed by the first tap on Send, fired by the second. A broadcast has no undo, so it is
    // the one send in the app that asks — the same second-tap confirm the destructive verbs on
    // the chat-details screen use. Any edit to the message disarms it: the thing you confirmed
    // is no longer the thing that would go out.
    var confirming by remember(batch.id) { mutableStateOf(false) }

    val progress = state.newsletterProgress
    // Any broadcast in flight replaces the bar, not just this batch's: the ViewModel runs one at
    // a time, so offering Send here while another batch is going out only produces a refusal.
    val sending = progress != null && !progress.done
    val canSend = input.isNotBlank() || attached.isNotEmpty()

    if (picking) {
        BackHandler { picking = false }
        PhotoPickerScreen(
            // The picker's verb is "Send" everywhere else in the app; here it attaches.
            // Appending rather than replacing lets a second trip through it add to what is
            // already on the message.
            onSend = { files ->
                picking = false
                if (files.isNotEmpty()) {
                    attached = attached + files
                    confirming = false
                }
            },
            onClose = { picking = false },
        )
        return
    }

    val listState = rememberLazyListState()
    WheelScroll(listState)

    Column(modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = batch.name.ifBlank { NewsletterBatch.UNTITLED },
            onBack = viewModel::closeNewsletterCompose,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        Text(
            text = "Goes out separately to ${batch.targets.size} " +
                if (batch.targets.size == 1) "recipient" else "recipients",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(batch.targets.size, key = { batch.targets[it].key }) { i ->
                Text(
                    text = batch.targets[i].label,
                    style = ChatType.meta,
                    color = ChatColors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            if (attached.isNotEmpty()) {
                item(key = "photos") {
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
                        Spacer(modifier = Modifier.height(8.dp))
                        attached.forEach { file ->
                            HapticText(
                                text = "${file.name} ×",
                                style = ChatType.hint,
                                color = ChatColors.onSurfaceVariant,
                                textAlign = TextAlign.Start,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                onClick = {
                                    attached = attached.filterNot { it.path == file.path }
                                    confirming = false
                                },
                            )
                        }
                    }
                }
            }
        }

        // Progress / outcome. While a send is running this is the only thing on screen saying
        // so, which is why it sits directly above the compose bar rather than in the header.
        // **Named when it isn't ours.** Backing out of a broadcast doesn't stop it, so the one
        // in flight here can belong to a different batch — and an unlabelled "Sending 3/40…" on
        // this screen reads as *this* batch going out, which is the misreport the batch id on
        // the progress exists to prevent.
        progress?.let {
            HapticText(
                text = if (it.batchId == batch.id) it.line() else "${it.batchName}: ${it.line()}",
                style = ChatType.hint,
                color = ChatColors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                onClick = viewModel::clearNewsletterProgress,
            )

            // The grid. Only for this batch: another batch's rows under this batch's header
            // would read as this one's recipients.
            if (it.batchId == batch.id && it.rows.isNotEmpty() && it.error == null) {
                NewsletterGrid(it)
                if (it.done && it.missingCount() > 0) {
                    val n = it.missingCount()
                    Text(
                        text = "RESEND $n MISSING",
                        style = ChatType.hint,
                        color = ChatColors.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = viewModel::resendNewsletterMissing)
                            .padding(vertical = 10.dp),
                    )
                    Text(
                        text = "Only the items above that didn't land, and only to the people " +
                            "missing them. Nobody is sent anything twice.",
                        style = ChatType.hint,
                        color = ChatColors.onSurfaceDisabled,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
            }
        }

        if (sending) {
            // The bar is replaced, not disabled: a field that still takes keystrokes but whose
            // Send does nothing is the shape of a bug report.
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "Sending…", style = ChatType.body, color = ChatColors.onSurfaceDisabled)
            }
        } else {
            if (confirming) {
                Text(
                    text = "Send again to confirm — ${batch.targets.size} " +
                        if (batch.targets.size == 1) "recipient" else "recipients",
                    style = ChatType.hint,
                    color = ChatColors.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            // A hand-rolled bar rather than the shared [ComposeBar], and the difference is not
            // cosmetic: that one owns its own text and empties it the moment Send is tapped,
            // which here would throw away the message on the *arming* tap; its Send is also
            // inert on an empty field, and a broadcast may legitimately be photos with no
            // words. Three behaviours differ, so it is its own bar.
            HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                HapticText(
                    text = "+",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    onClick = { picking = true },
                )
                Spacer(modifier = Modifier.width(16.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (input.isEmpty()) {
                        Text(
                            text = "Message",
                            style = ChatType.body,
                            color = ChatColors.onSurfaceDisabled,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it; confirming = false },
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
                    color = if (canSend) ChatColors.onSurface else ChatColors.onSurfaceDisabled,
                    onClick = {
                        if (!canSend) return@HapticText
                        if (confirming) {
                            confirming = false
                            viewModel.sendNewsletter(batch, input, attached)
                            input = ""
                            attached = emptyList()
                        } else {
                            confirming = true
                        }
                    },
                )
            }
        }
    }
}

/**
 * One row per recipient, one square per item, filled in as each lands.
 *
 * The question this answers is "which photo is missing, for whom", which a counter cannot
 * answer at all. Squares rather than a progress bar because the useful reading is positional:
 * the third square being empty down several rows is a photo that is failing, not a person who
 * is unreachable, and that distinction is the whole diagnosis.
 *
 * Scrolls on its own for a long batch, capped so the compose bar never leaves the screen -- the
 * grid is worth nothing if it pushes away the thing you tapped to make it appear.
 */
@Composable
private fun NewsletterGrid(progress: com.gios.lightchat.NewsletterProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
    ) {
        progress.rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    style = ChatType.hint,
                    color = if (row.allSent) ChatColors.onSurfaceDisabled else ChatColors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                row.states.forEach { state ->
                    Box(
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(10.dp)
                            .border(1.dp, ChatColors.onSurfaceVariant)
                            .background(
                                when (state) {
                                    SendState.Sent -> ChatColors.onSurface
                                    // Halfway, so "in flight" is not mistaken for "arrived" on
                                    // a panel with no colour to tell them apart with.
                                    SendState.Sending -> ChatColors.onSurfaceDisabled
                                    else -> Color.Transparent
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // A failure is not an empty square: pending and failed are both empty,
                        // and at the end of a send they mean very different things.
                        if (state == SendState.Failed) {
                            Text(
                                text = "\u00d7",
                                style = ChatType.hint,
                                color = ChatColors.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
