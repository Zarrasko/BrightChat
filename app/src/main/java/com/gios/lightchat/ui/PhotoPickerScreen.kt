package com.gios.lightchat.ui

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.core.content.FileProvider
import com.gios.lightchat.Gallery
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

    var notice by remember { mutableStateOf<String?>(null) }
    val takePhoto = rememberCameraLauncher(
        onPhoto = { file -> onSend(listOf(file)) },
        // A camera that ignored EXTRA_OUTPUT and saved to DCIM its own way will simply
        // be at the top of the grid after a rescan.
        onNothing = { reload++ },
        onNoCamera = { notice = "No camera app on this phone" },
    )

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
                onClick = takePhoto,
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
                    text = notice ?: if (granted) "Tap to select" else "",
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
 * Hands off to whatever camera LightOS ships and returns the photo.
 *
 * Two ways back, because a camera app's handling of `ACTION_IMAGE_CAPTURE` varies and
 * this one's is unknown: the file we asked it to write via EXTRA_OUTPUT, else the
 * low-resolution thumbnail some return in the result extras instead. If neither
 * produced anything, [onNothing] rescans — a camera that ignored EXTRA_OUTPUT and
 * saved to DCIM in its own way will then simply be in the grid, newest first.
 *
 * A camera photo sends straight away rather than joining the selection: you opened
 * the camera and pressed the shutter, so there's no ambiguity to confirm.
 */
@Composable
private fun rememberCameraLauncher(
    onPhoto: (File) -> Unit,
    onNothing: () -> Unit,
    onNoCamera: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var output by remember { mutableStateOf<File?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // RESULT_OK only. Some cameras write the EXTRA_OUTPUT file before their own
        // confirm/retake step, so a non-empty file after a cancel is not a photo the
        // user chose to send — and this path sends immediately.
        if (result.resultCode != Activity.RESULT_OK) { onNothing(); return@rememberLauncherForActivityResult }
        val ours = output?.takeIf { it.exists() && it.length() > 0L }
        if (ours != null) { onPhoto(ours); return@rememberLauncherForActivityResult }

        val thumbnail = result.data?.extras?.let {
            @Suppress("DEPRECATION")
            it.get("data") as? Bitmap
        }
        if (thumbnail != null) {
            val file = cameraFile(context)
            runCatching {
                file.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            }.onSuccess { onPhoto(file); return@rememberLauncherForActivityResult }
        }
        onNothing()
    }

    return {
        pruneCameraCache(context)
        val file = cameraFile(context)
        output = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, uri)
        // Belt and braces: startActivityForResult migrates EXTRA_OUTPUT into ClipData
        // and adds the read + write grants itself, but being explicit costs nothing.
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        // Not swallowed: if LightOS ships no ACTION_IMAGE_CAPTURE handler, a Camera
        // control that does nothing at all is worse than one that says so.
        runCatching { launcher.launch(intent) }.onFailure { onNoCamera() }
    }
}

private fun cameraFile(context: android.content.Context): File {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    return File(dir, "cam-" + System.currentTimeMillis() + ".jpg")
}

/** Full-res JPEGs left behind by earlier captures. Nothing else clears them, and the
 *  bytes are already in the sent message's own attachment cache. */
private fun pruneCameraCache(context: android.content.Context) {
    runCatching { File(context.cacheDir, "camera").listFiles()?.forEach { it.delete() } }
}

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
