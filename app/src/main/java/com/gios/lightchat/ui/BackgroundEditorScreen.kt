package com.gios.lightchat.ui

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ChatBackground
import com.gios.lightchat.ColorMode
import com.gios.lightchat.Gallery
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File
import kotlinx.coroutines.launch

/**
 * The background editor for one conversation: pick a photo, choose how it meets
 * the screen (fill / fit / stretch), then stack filters on it — dither, black &
 * white, opacity, corner blur, corner fade — reordering and repeating them
 * freely, with a live preview at the screen's own aspect so what you see is
 * what the thread gets.
 *
 * Two states, one screen: with no photo chosen yet it opens straight into a
 * picker grid (the same DCIM/Pictures walk as [Gallery] — the system picker is
 * as useless here as it is for sending). With one, it's the preview over the
 * stack. "Photo" in the header goes back to the grid without losing the stack,
 * so re-choosing the picture keeps the filters you tuned.
 *
 * Nothing is written until Save: the stack is edited against a local copy of
 * the saved state, so backing out abandons it, the same bargain every editor
 * on this phone makes.
 */
@Composable
fun BackgroundEditorScreen(chatGuid: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // The image being worked on: the already-saved copy when editing, or the
    // DCIM file just tapped in the grid. Null means the grid is showing.
    var source by remember {
        mutableStateOf(ChatBackground.sourceFile(context, chatGuid).takeIf { it.length() > 0L })
    }
    // The working stack. A plain state list — order is the whole point of a stack,
    // and every mutation below recomposes the preview through it.
    val filters = remember {
        mutableStateListOf<ChatBackground.Filter>().apply {
            addAll(ChatBackground.filters(context, chatGuid))
        }
    }
    // How the photo lands on the screen before the filters run.
    var scale by remember { mutableStateOf(ChatBackground.scale(context, chatGuid)) }
    // Which filter's "add" menu is open, if any.
    var adding by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // Whether the photo grid is up. A flag rather than nulling [source], so backing
    // out of a re-pick returns to the editor with the stack intact instead of
    // abandoning the whole edit.
    var picking by remember { mutableStateOf(source == null) }

    // The thread draws the background edge to edge, so the preview crops to the
    // screen's own shape — corner blur in particular has to land where the real
    // corners will be.
    val configuration = LocalConfiguration.current
    val aspect = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp

    if (picking) {
        BackgroundPickerGrid(
            onPick = {
                source = it
                picking = false
            },
            // Backing out of a re-pick is not backing out of the edit.
            onClose = { if (source != null) picking = false else onClose() },
        )
        return
    }
    val chosen = source ?: return
    BackHandler { onClose() }

    // Re-rendered on every change to the stack; small (see ChatBackground.PREVIEW_DIM),
    // so a tap on −/+ answers within a frame or two. The previous preview is kept on
    // screen while the next one renders — a flash of empty black on every nudge would
    // read as flicker.
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    val stackKey = scale.name + filters.joinToString { "${it.type.name}:${it.amount}" }
    LaunchedEffect(chosen, stackKey) {
        preview = ChatBackground.preview(chosen, filters.toList(), scale, aspect) ?: preview
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "Background",
            onBack = onClose,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            trailing = {
                HapticText(
                    text = if (saving) "…" else "Save",
                    style = ChatType.hint,
                    color = ChatColors.onSurface,
                    onClick = {
                        if (saving) return@HapticText
                        saving = true
                        scope.launch {
                            ChatBackground.save(context, chatGuid, chosen, filters.toList(), scale)
                            onClose()
                        }
                    },
                )
            },
        )

        // The preview, at the screen's aspect but never more than half the height —
        // the stack below it is the thing being worked, and it needs room.
        Box(
            modifier = Modifier
                .fillMaxWidth(0.62f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(aspect)
                .background(ChatColors.onSurfaceDisabled.copy(alpha = 0.12f)),
        ) {
            preview?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Background preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // A sample line of each side's text over the preview, because the whole
            // question a background has to answer is whether words survive on it.
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(text = "Them", style = ChatType.hint, color = ChatColors.onSurface)
                Text(
                    text = "You",
                    style = ChatType.hint,
                    color = ChatColors.onSurface,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HapticText(
            text = "Choose a different photo",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDim,
            modifier = Modifier.fillMaxWidth(),
            onClick = { picking = true },
        )
        Spacer(modifier = Modifier.height(12.dp))
        // How the photo meets the screen: fill and crop, fit on black (which the
        // corner effects then dissolve into), or stretch. One row, the chosen word lit.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Scale",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                modifier = Modifier.weight(1f),
            )
            ChatBackground.ScaleMode.entries.forEach { mode ->
                Spacer(modifier = Modifier.width(16.dp))
                HapticText(
                    text = mode.label,
                    style = ChatType.hint,
                    color = if (scale == mode) ChatColors.onSurface else ChatColors.onSurfaceDim,
                    onClick = { scale = mode },
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)

        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Filters",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
            )
            if (filters.isEmpty()) {
                Text(
                    text = "None — the photo as it is",
                    style = ChatType.meta,
                    color = ChatColors.onSurfaceDim,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            filters.forEachIndexed { index, filter ->
                FilterRow(
                    filter = filter,
                    // Applied top-to-bottom; ↑ moves a filter earlier in the pass.
                    canMoveUp = index > 0,
                    onMoveUp = {
                        filters.removeAt(index)
                        filters.add(index - 1, filter)
                    },
                    onAmount = { up ->
                        filters[index] = filter.copy(amount = filter.type.bump(filter.amount, up))
                    },
                    onRemove = { filters.removeAt(index) },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (adding) {
                ChatBackground.FilterType.entries.forEach { type ->
                    HapticText(
                        text = type.label,
                        style = ChatType.body,
                        color = ChatColors.onSurfaceVariant,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        onClick = {
                            filters.add(ChatBackground.Filter(type, type.default))
                            adding = false
                        },
                    )
                }
            } else {
                HapticText(
                    text = "Add a filter",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    onClick = { adding = true },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * One filter in the stack: its name and setting on the left, the verbs on the
 * right — −/+ nudge the amount (hidden for black & white, which has none),
 * ↑ moves it earlier in the pass, × takes it out. All text, like every verb on
 * this phone; the stack rarely runs past three, so rows over menus.
 */
@Composable
private fun FilterRow(
    filter: ChatBackground.Filter,
    canMoveUp: Boolean,
    onMoveUp: () -> Unit,
    onAmount: (up: Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOf(filter.type.label, filter.type.display(filter.amount))
                .filter { it.isNotEmpty() }
                .joinToString(" "),
            style = ChatType.body,
            color = ChatColors.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (filter.type.hasAmount) {
            HapticText(
                text = "−",
                style = ChatType.body,
                color = if (filter.amount > filter.type.min) ChatColors.onSurfaceDim else ChatColors.onSurfaceDisabled,
                modifier = Modifier.width(32.dp),
                onClick = { onAmount(false) },
            )
            HapticText(
                text = "+",
                style = ChatType.body,
                color = if (filter.amount < filter.type.max) ChatColors.onSurfaceDim else ChatColors.onSurfaceDisabled,
                modifier = Modifier.width(32.dp),
                onClick = { onAmount(true) },
            )
        }
        HapticText(
            text = "↑",
            style = ChatType.body,
            color = if (canMoveUp) ChatColors.onSurfaceDim else ChatColors.onSurfaceDisabled,
            modifier = Modifier.width(32.dp),
            onClick = { if (canMoveUp) onMoveUp() },
        )
        HapticText(
            text = "×",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            modifier = Modifier.width(32.dp),
            onClick = onRemove,
        )
    }
}

/**
 * The single-tap photo grid the editor opens with. The multi-select picker's
 * sibling, not the picker itself: choosing a background is one photo, and the
 * explicit-Send guard that stops a stray tap *messaging somebody* has nothing
 * to protect here — the tap only moves to the editor, and nothing is saved
 * until its Save.
 *
 * Holds [ColorMode] while it's up, same as sending: choosing a photograph in
 * greyscale is guesswork. The editor after it deliberately doesn't — the
 * thread will draw the result in greyscale, so tuning the filters in greyscale
 * is tuning them honestly.
 */
@Composable
private fun BackgroundPickerGrid(onPick: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Gallery.permission) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var asked by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted && !asked) { asked = true; askPermission.launch(Gallery.permission) }
    }

    DisposableEffect(Unit) {
        ColorMode.acquire(context)
        onDispose { ColorMode.release(context) }
    }

    val photos by produceState<List<Gallery.Photo>?>(null, granted) {
        value = null
        value = if (granted) Gallery.scan() else emptyList()
    }

    val gridState = rememberLazyGridState()
    WheelScroll(gridState)
    BackHandler { onClose() }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        ScreenHeader(
            title = "Choose a photo",
            onBack = onClose,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
        )
        val loaded = photos
        when {
            !granted -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "LightChat needs access to all photos to\nread the camera roll.",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    textAlign = TextAlign.Center,
                )
            }
            loaded == null -> Spacer(modifier = Modifier.weight(1f))
            loaded.isEmpty() -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No photos in DCIM or Pictures.",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDisabled,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(loaded, key = { it.key }) { photo ->
                    val thumb by produceState<ImageBitmap?>(null, photo.key) {
                        value = Gallery.thumbnail(photo)
                    }
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .background(ChatColors.onSurfaceDisabled.copy(alpha = 0.12f))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPick(photo.file)
                            },
                    ) {
                        thumb?.let {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
