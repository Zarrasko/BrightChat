@file:OptIn(ExperimentalFoundationApi::class)

package com.gios.lightchat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ColorMode
import com.gios.lightchat.Gif
import com.gios.lightchat.Gifs
import com.gios.lightchat.api.KlipyApi
import com.gios.lightchat.api.Store
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The GIF picker — the thing every messaging app has and this one didn't.
 *
 * Three lists behind one row of words: **Trending** (or the results of whatever is typed),
 * **Saved**, and **Recent**. Same idea as the conversation list's tabs, and the same reason: on a
 * panel this size a tab strip costs nothing to read and a menu costs a tap and a redraw.
 *
 * ### Two taps to send, one to save
 *
 * Discord sends a GIF on the tap. This does not, and the precedent is the photo picker's, which
 * has the comment explaining why: an immediate send is one stray thumb away from putting something
 * in front of somebody. So a tap **arms** a GIF — outlined, and the foot of the screen becomes
 * *Send* — and the send happens on the second tap, either on Send or on the armed cell itself.
 *
 * **Long-press saves.** Exactly like long-pressing a row in the conversation list to star it, and
 * the mark appearing in the corner is the only confirmation. See [Gifs] for what saving actually
 * does — it keeps the file, so a saved GIF works with no key and no tunnel.
 *
 * ### Without a key
 *
 * Searching needs one ([Store.klipyKey]); Saved and Recent do not. With no key the screen opens on
 * Saved and says what is missing rather than showing an empty grid or an error, because "GIFs are
 * broken" and "you haven't pasted a key in yet" look identical otherwise.
 */
@Composable
fun GifPickerScreen(onSend: (Gif) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val canSearch = remember { Store.canSearchGifs(context) }
    val customerId = remember { if (canSearch) Store.gifCustomerId(context) else "" }

    var tab by rememberSaveable { mutableStateOf(if (canSearch) GifTab.Find else GifTab.Saved) }
    var query by rememberSaveable { mutableStateOf("") }

    // Saved and recent are read from the store rather than from a ViewModel: both are this
    // screen's own state, written only here, and putting them in UiState would mean every
    // arriving message recomposing a grid of animated GIFs. Re-read on [saves], which the
    // long-press bumps.
    var saves by remember { mutableStateOf(0) }
    val saved = remember(saves) { Gifs.saved(context) }
    val recents = remember(saves) { Gifs.recents(context) }

    // What is on its way from the service, and what came back. `request` is one value rather than
    // a query and a page side by side, because it is the loader's only key: with two keys, a query
    // changing while page 3 was showing fired a stale fetch that appended the new query's page 3
    // to the old query's results.
    var request by remember { mutableStateOf(GifRequest("", 1)) }
    var results by remember { mutableStateOf<List<Gif>>(emptyList()) }
    var hasNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    // Typing settles before anything is fetched. A search per keystroke on this keyboard is a
    // request per letter, all but the last one wasted, against a key with an hourly allowance.
    LaunchedEffect(query) {
        delay(SEARCH_SETTLE_MS)
        val settled = query.trim()
        if (settled != request.query) request = GifRequest(settled, 1)
    }

    LaunchedEffect(request, canSearch) {
        if (!canSearch) return@LaunchedEffect
        loading = true
        failure = null
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val api = Gifs.api(context) ?: error("No GIF key")
                if (request.query.isBlank()) {
                    api.trending(page = request.page, customerId = customerId)
                } else {
                    api.search(request.query, page = request.page, customerId = customerId)
                }
            }
        }
        loading = false
        outcome
            .onSuccess { page ->
                // Page 1 replaces, later pages append — the same list either way, so a fetch that
                // lands after the query moved on cannot half-fill the grid with the wrong thing.
                //
                // **Deduplicated by id**, which is not tidiness: the grid keys its cells on the id,
                // and a `LazyVerticalGrid` handed the same key twice throws. Paged search results
                // repeat entries whenever the ranking shifts under a page boundary — which for a
                // trending list is all the time.
                results = (if (request.page == 1) page.gifs else results + page.gifs)
                    .distinctBy { it.id }
                // A page that came back empty is the end of the list, whatever the envelope said:
                // the grid asks for the next page whenever the last row is near, so a service
                // claiming `has_next` forever would page for as long as the picker was open.
                hasNext = page.hasNext && page.gifs.isNotEmpty()
            }
            .onFailure { failure = it.message?.takeIf { m -> m.isNotBlank() } ?: "Couldn’t reach the GIF service" }
    }

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Colour, for as long as the picker is up — the same hold the photo picker takes, and for a
    // stronger reason: a GIF is usually a colour joke, and choosing one in greyscale is choosing
    // half of it. A no-op without the one-time WRITE_SECURE_SETTINGS grant.
    DisposableEffect(Unit) {
        ColorMode.acquire(context)
        onDispose { ColorMode.release(context) }
    }

    WheelScroll(gridState)

    val shown = when (tab) {
        GifTab.Find -> results
        GifTab.Saved -> saved
        GifTab.Recent -> recents
    }

    // The next page, fetched while the last row is still coming into view rather than when it is
    // reached. Only the searched list pages; saved and recent are as long as they are.
    LaunchedEffect(gridState, tab, hasNext, loading, results.size) {
        if (tab != GifTab.Find || !hasNext || loading) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (results.isNotEmpty() && last >= results.size - PAGE_AHEAD) {
                    request = request.copy(page = request.page + 1)
                }
            }
    }

    // Which GIF is armed to send. Cleared by switching tabs, so a cell armed in Saved cannot be
    // sent by a Send tap taken after moving to Trending.
    var armed by remember(tab) { mutableStateOf<Gif?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HapticText(
                text = "‹",
                style = ChatType.title,
                color = ChatColors.onSurface,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.Start,
                onClick = onClose,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(text = "GIF", style = ChatType.body, color = ChatColors.onSurfaceVariant)
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(56.dp))
        }

        if (canSearch) {
            SearchField(
                value = query,
                onChange = {
                    query = it
                    // Typing is a search, so it moves you to the list that answers it — nothing
                    // is more annoying than a search box that fills a grid you can't see.
                    tab = GifTab.Find
                },
            )
            HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            for (entry in GifTab.entries) {
                HapticText(
                    text = entry.label,
                    style = ChatType.hint,
                    color = if (entry == tab) ChatColors.onSurface else ChatColors.onSurfaceInactive,
                    onClick = { tab = entry },
                )
            }
        }

        val note = when {
            tab == GifTab.Find && failure != null -> failure
            tab == GifTab.Find && !canSearch ->
                "Add a GIF key in Settings to search.\nSaved GIFs work without one."
            tab == GifTab.Find && loading && shown.isEmpty() -> ""
            tab == GifTab.Find && shown.isEmpty() && request.query.isNotBlank() -> "Nothing for “${request.query}”."
            tab == GifTab.Find && shown.isEmpty() -> ""
            tab == GifTab.Saved && shown.isEmpty() -> "Hold a GIF to save it here."
            tab == GifTab.Recent && shown.isEmpty() -> "GIFs you send show up here."
            else -> null
        }

        if (note != null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = note,
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(GAP.dp),
                verticalArrangement = Arrangement.spacedBy(GAP.dp),
                contentPadding = PaddingValues(bottom = GAP.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(shown, key = { it.id }) { gif ->
                    GifCell(
                        gif = gif,
                        armed = armed?.id == gif.id,
                        saved = saved.any { it.id == gif.id },
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            // A tap on the already-armed cell is the second tap, so it sends —
                            // which makes "double-tap a GIF to send it" true without making a
                            // single stray tap send anything.
                            if (armed?.id == gif.id) onSend(gif) else armed = gif
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val nowSaved = Gifs.toggleSaved(context, gif)
                            saves++
                            // Saving means keeping the file, and at this point the phone has only
                            // the *preview* rendition — the grid never downloads the sendable one.
                            // So fetch it now, while there is a connection to fetch it over:
                            // waiting until the GIF is next opened would mean a favourite saved on
                            // wifi and unusable on a train.
                            if (nowSaved) scope.launch { Gifs.file(context, gif) }
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val target = armed
            if (target != null) {
                HapticText(
                    text = "Send",
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    onClick = { onSend(target) },
                )
            } else {
                // KLIPY's branding guidelines ask for this line and for the search placeholder
                // above, which is a fair price for the library. It doubles as the answer to
                // "where are these coming from?", which on a phone bought to have less of the
                // internet on it is a question worth answering.
                Text(
                    text = KlipyApi.ATTRIBUTION,
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                )
            }
        }
    }
}

/** The three lists. Ordered as they are drawn, and named as they are labelled. */
private enum class GifTab(val label: String) {
    Find("Trending"),
    Saved("Saved"),
    Recent("Recent"),
}

/** One fetch: a query (blank meaning trending) and a page. See why it is one value, not two. */
private data class GifRequest(val query: String, val page: Int)

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(ChatColors.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    text = KlipyApi.SEARCH_HINT,
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            inner()
        },
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

/**
 * One cell: the GIF, playing.
 *
 * The preview rendition, not the sendable one — a screenful of full-size GIFs is several megabytes
 * of download and several times the memory. A saved GIF is drawn from its kept copy instead, so
 * the Saved tab never touches the network (see [Gifs.thumb]).
 *
 * The cell is shaped by the GIF's own aspect ratio rather than squared off, because a GIF is
 * usually cropped to its joke already and squaring it cuts the punchline.
 */
@Composable
private fun GifCell(gif: Gif, armed: Boolean, saved: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val file by produceState<File?>(null, gif.id, saved) { value = Gifs.thumb(context, gif) }
    val painter = rememberGifPainter(file)

    Box(
        modifier = Modifier
            .aspectRatio(gif.ratio)
            .background(ChatColors.background)
            .then(if (armed) Modifier.border(2.dp, ChatColors.onSurface) else Modifier)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = gif.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Its title while it loads, and its title forever if it will not: a cell that says
            // "dog high five" is more use than an empty grey square.
            Text(
                text = gif.label,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(6.dp),
            )
        }
        if (saved) {
            // On a solid block in the corner, like the photo picker's pick number, because what
            // is behind it is an arbitrary picture. Drawn rather than typeset: Public Sans has no
            // star glyph, and a missing glyph falls back to another font — the bug that made the
            // tapbacks vector paths in the first place.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(ChatColors.background)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
            ) {
                StarMark(modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** A filled five-pointed star, computed rather than parsed: five points is trigonometry, and a
 *  path string for it would be a magic number nobody could check. */
@Composable
private fun StarMark(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val outer = size.minDimension / 2f
        val inner = outer * 0.42f
        val centre = Offset(size.width / 2f, size.height / 2f)
        val path = Path()
        // Ten vertices, alternating outer and inner, starting at the top point (-90°).
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.toRadians((i * 36.0) - 90.0)
            val point = Offset(
                centre.x + (radius * kotlin.math.cos(angle)).toFloat(),
                centre.y + (radius * kotlin.math.sin(angle)).toFloat(),
            )
            if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        path.close()
        drawPath(path, ChatColors.onSurface)
    }
}

private const val COLUMNS = 2
private const val GAP = 2

/** How long typing has to stop before it becomes a search. Long enough that a word typed on this
 *  keyboard is one request, short enough that it never feels like the box is ignoring you. */
private const val SEARCH_SETTLE_MS = 350L

/** How many cells from the end the next page is asked for. Two rows: the fetch is in flight
 *  before the last row is on screen, so the grid grows rather than stalling. */
private const val PAGE_AHEAD = 4
