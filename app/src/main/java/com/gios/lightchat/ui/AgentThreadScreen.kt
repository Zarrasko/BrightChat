package com.gios.lightchat.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.AgentMessage
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Role
import com.gios.lightchat.markdown.Markdown
import com.gios.lightchat.markdown.MarkdownView
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The agent chat thread — the "separate system" twin of ThreadScreen. Same shape (list,
 * compose bar, typing indicator), but agent turns render as markdown (with inline images)
 * and everything is local: no BlueBubbles, no socket, just the AgentStore + AgentApi.
 */
@Composable
fun AgentThreadScreen(viewModel: ChatViewModel) {
    val state by viewModel.state.collectAsState()
    val agent = state.openAgent ?: return
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val imageLoader: suspend (String) -> Bitmap? = remember { { url -> loadAgentImage(context, url) } }

    // Keep the newest turn in view; the typing dots count as a row while sending.
    LaunchedEffect(state.agentMessages.size, state.agentSending) {
        val last = state.agentMessages.size - 1 + if (state.agentSending) 1 else 0
        if (last >= 0) listState.animateScrollToItem(last)
    }
    WheelScroll(listState)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = agent.name,
            onBack = viewModel::closeAgent,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.agentMessages, key = { it.id }) { msg ->
                AgentRow(msg, imageLoader)
            }
            if (state.agentSending) item { TypingDots() }
        }
        state.message?.let { m ->
            Text(
                text = m,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
        }
        ComposeBar(onSend = viewModel::sendAgentMessage)
    }
}

/** A user turn (plain, right) or an agent turn (markdown, left). */
@Composable
private fun AgentRow(message: AgentMessage, loadImage: suspend (String) -> Bitmap?) {
    val fromMe = message.role == Role.USER
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalAlignment = if (fromMe) Alignment.End else Alignment.Start,
    ) {
        if (fromMe) {
            Text(
                text = message.text,
                style = ChatType.body,
                color = ChatColors.onSurface,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(0.8f),
            )
        } else {
            val blocks = remember(message.text) { Markdown.parse(message.text) }
            MarkdownView(
                blocks = blocks,
                modifier = Modifier.fillMaxWidth(0.9f),
                textStyle = ChatType.body,
                contentColor = ChatColors.onSurface,
                loadImage = loadImage,
            )
        }
    }
}

@Composable
private fun TypingDots() {
    var dots by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(400)
            dots = (dots % 3) + 1
        }
    }
    Text(
        text = "•".repeat(dots),
        style = ChatType.body,
        color = ChatColors.onSurfaceDim,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
        textAlign = TextAlign.Start,
    )
}

/** Downloads and caches a markdown image, downsampling to ~1080px like Attachments does. */
private suspend fun loadAgentImage(context: android.content.Context, url: String): Bitmap? =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "agent-images").apply { mkdirs() }
        val file = File(dir, "img-" + url.hashCode().toString())
        if (file.exists() && file.length() > 0) {
            decodeScaled(file.absolutePath)
        } else {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                decodeScaled(file.absolutePath)
            } catch (e: Exception) {
                null
            }
        }
    }

private fun decodeScaled(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1080) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
