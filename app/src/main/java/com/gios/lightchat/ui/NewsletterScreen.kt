package com.gios.lightchat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.NewsletterBatch
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType

/**
 * The newsletter batch list — the front door of the feature.
 *
 * A batch is a named set of recipients (group chats and contacts, mixed) that one message goes
 * out to, separately, one thread each. Tap a batch to write to it; long-press to edit who is in
 * it. "New batch" makes one.
 *
 * Long-press for edit rather than a second verb on the row, matching the conversation list next
 * door: on a 3.92" panel a row that carries a name, a recipient count *and* an Edit target ends
 * up with three tap regions no thumb can tell apart.
 */
@Composable
fun NewsletterScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    WheelScroll(listState)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "Newsletter",
            onBack = viewModel::closeNewsletters,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )

        HapticText(
            text = "New batch",
            style = ChatType.body,
            color = ChatColors.onSurfaceVariant,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            onClick = viewModel::newNewsletterBatch,
        )
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        if (state.newsletters.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No batches yet.\nA batch is a list of chats and\ncontacts one message goes out to.",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(state.newsletters, key = { it.id }) { batch ->
                    BatchRow(
                        batch = batch,
                        onClick = { viewModel.openNewsletterCompose(batch) },
                        onLongClick = { viewModel.editNewsletterBatch(batch) },
                    )
                }
            }
        }

        // The broadcast in flight, or the outcome of the last one — this screen is where you
        // land after backing out of a send, and it is the only place a running one is still
        // visible. Always named, since from here there is no "current" batch to imply.
        // Tapping it dismisses a finished one.
        state.newsletterProgress?.let { progress ->
            HapticText(
                text = "${progress.batchName}: ${progress.line()}",
                style = ChatType.hint,
                color = ChatColors.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                onClick = viewModel::clearNewsletterProgress,
            )
        }
    }
}

@Composable
private fun BatchRow(
    batch: NewsletterBatch,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        HapticText(
            text = batch.name.ifBlank { NewsletterBatch.UNTITLED },
            style = ChatType.body,
            color = ChatColors.onSurface,
            textAlign = TextAlign.Start,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
            onLongClick = onLongClick,
            onClick = onClick,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = batch.summary(),
            style = ChatType.meta,
            color = ChatColors.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
