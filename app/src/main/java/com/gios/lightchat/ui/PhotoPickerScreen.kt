package com.gios.lightchat.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.gios.lightchat.ColorMode
import com.gios.lightchat.Gallery
import com.gios.light.common.hw.WheelScroll
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File

/**
 * The photo picker, replacing the system one.
 *
 * The system picker is backed by MediaStore, which on LightOS nothing keeps current,
 * so photos you just took aren't in it. [Gallery] reads DCIM and Pictures directly
 * instead — see there for why. This screen is the grid over that: tap to select (in
 * order, each selected cell showing its number), Send fires them as separate
 * attachments in the order you picked.
 *
 * Multi-select plus an explicit Send is also what stops the old failure mode where
 * one stray tap on the picker sent a photo to somebody.
 */
@Composable
fun PhotoPickerScreen(
    onSend: (List<File>) -> Unit,
    onClose: () -> Unit,
) {
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

    // Bumped to force a rescan — after the camera returns, mostly.
    var reload by remember { mutableIntStateOf(0) }
    // null while the scan is running, so an empty grid isn't reported as "no photos"
    // for the frame or two the walk takes.
    val photos by produceState<List<Gallery.Photo>?>(null, granted, reload) {
        // Cleared first: produceState remembers its state unkeyed, so on a re-run
        // (permission granted, or a rescan after the camera) `value` would still hold
        // the previous result — an empty list from the ungranted pass reads as "no
        // photos" for the whole time the walk takes.
        value = null
        value = if (granted) Gallery.scan() else emptyList()
    }

    // Paths, not indices: the list is rescanned under us. Ordered, because the
    // number drawn on a cell is its position in the send.
    val selected = remember { mutableStateListOf<String>() }
    val gridState = rememberLazyGridState()

    // The viewfinder is a state of this screen rather than another app, so the colour
    // hold below covers it and nothing leaves LightChat.
    var capturing by rememberSaveable { mutableStateOf(false) }

    // True colour for as long as the picker or the camera is up. Choosing a photo in
    // greyscale is guesswork, and framing one is worse. Held here rather than in
    // CameraScreen so it spans both: this effect sits above the early return, so it
    // stays alive while the viewfinder is up. A no-op without the one-time
    // WRITE_SECURE_SETTINGS grant.
    //
    // No fade on the way out, unlike ImageViewerScreen. Known gap: the thread does draw
    // inline photo thumbnails, and the secure-settings write takes ~70ms to reach
    // SurfaceFlinger, so for about that long after the picker closes those thumbnails
    // are still in colour and then visibly desaturate. Hiding it would mean the viewer's
    // whole fade-to-black sequence; the thread's own text is white on black either way,
    // so what's left is a couple of frames on the thumbnails.
    DisposableEffect(Unit) {
        ColorMode.acquire(context)
        onDispose { ColorMode.release(context) }
    }

    if (capturing) {
        BackHandler { capturing = false }
        CameraScreen(
            onCaptured = { file -> onSend(listOf(file)) },
            onClose = {
                capturing = false
                // A capture that was kept in DCIM should be in the grid on the way back.
                reload++
            },
        )
        return
    }

    // Below the early return rather than beside the state it points at: with the
    // viewfinder up the grid is off screen, and a wheel wired above this line would
    // scroll it out of sight while you were framing a photograph.
    WheelScroll(gridState)

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
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
            Text(
                text = "Photos",
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            HapticText(
                text = "Camera",
                style = ChatType.hint,
                color = ChatColors.onSurfaceVariant,
                modifier = Modifier.width(56.dp),
                textAlign = TextAlign.End,
                onClick = {
                    pruneCameraCache(context)
                    capturing = true
                },
            )
        }

        val loaded = photos
        when {
            // Not a dead end, and not silent about the awkward case: Android 14's dialog
            // offers "Select photos and videos", which grants only partial access and
            // leaves READ_MEDIA_IMAGES denied — so the user thinks they allowed it and
            // the app says no. Reading DCIM by path needs the full grant, so say so and
            // give them somewhere to go. Reopening the picker re-checks.
            !granted -> Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "LightChat needs access to all photos to\nread the camera roll.",
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDisabled,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    HapticText(
                        text = "Open settings",
                        style = ChatType.body,
                        color = ChatColors.onSurface,
                        onClick = { openAppSettings(context) },
                    )
                }
            }
            loaded == null -> Centered("", Modifier.weight(1f))
            loaded.isEmpty() -> Centered("No photos in DCIM or Pictures.", Modifier.weight(1f))
            else -> LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(GAP.dp),
                verticalArrangement = Arrangement.spacedBy(GAP.dp),
                contentPadding = PaddingValues(vertical = GAP.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                items(loaded, key = { it.key }) { photo ->
                    val ordinal = selected.indexOf(photo.key)
                    PhotoCell(
                        photo = photo,
                        ordinal = if (ordinal >= 0) ordinal + 1 else null,
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            toggle(selected, photo.key)
                        },
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (selected.isEmpty()) {
                Text(
                    text = if (granted) "Tap to select" else "",
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                )
            } else {
                HapticText(
                    text = "Send " + selected.size,
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    onClick = {
                        // Resolve paths back to files in pick order, dropping anything
                        // that vanished between the scan and now.
                        val byPath = loaded.orEmpty().associateBy { it.key }
                        val files = selected.mapNotNull { byPath[it]?.file }
                        if (files.isNotEmpty()) onSend(files)
                    },
                )
            }
        }
    }
}

/** One grid cell. Selection is a white outline plus the pick number — no tint or
 *  checkmark, because the panel is greyscale and an outline is the one thing that
 *  reads over an arbitrary photo. */
@Composable
private fun PhotoCell(photo: Gallery.Photo, ordinal: Int?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val thumb by produceState<ImageBitmap?>(null, photo.key) {
        value = Gallery.thumbnail(photo)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(ChatColors.background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .then(
                if (ordinal != null) Modifier.border(2.dp, ChatColors.onSurface) else Modifier,
            ),
    ) {
        thumb?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (ordinal != null) {
            // On black, over the photo's own corner. The photo behind it is arbitrary,
            // so the number sits on a solid block rather than floating on the image.
            Text(
                text = ordinal.toString(),
                style = ChatType.hint,
                color = ChatColors.onSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(ChatColors.background)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun Centered(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = ChatType.body,
            color = ChatColors.onSurfaceDisabled,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Clears out old captures (see CameraScreen for where they're written). Nothing else
 * removes them and they're full frames, but only ones older than [CAPTURE_KEEP_MS] go:
 * a send reads the file on an IO coroutine, and deleting a capture from a moment ago
 * would be deleting one that might still be on its way out.
 */
private fun pruneCameraCache(context: android.content.Context) {
    val cutoff = System.currentTimeMillis() - CAPTURE_KEEP_MS
    runCatching {
        File(context.cacheDir, "camera").listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }
}

private const val CAPTURE_KEEP_MS = 5 * 60 * 1000L

private fun openAppSettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }
}

/** Add to the end (keeping pick order) or remove. */
private fun toggle(selected: SnapshotStateList<String>, key: String) {
    if (!selected.remove(key)) selected.add(key)
}

private const val COLUMNS = 3
private const val GAP = 2
