package com.gios.lightchat.ui

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * A sound somebody sent you, played here rather than handed to somebody else.
 *
 * The same reasoning as [VideoPlayerScreen], and it applies harder: a non-image attachment used to
 * download and go out as `ACTION_VIEW`, and on LightOS there is no audio player installed, so a
 * voice memo ended in "No app can open this file" after a download you had already waited for. A
 * voice memo is one of the most ordinary things in a message thread.
 *
 * **`MediaPlayer`, not ExoPlayer.** One local file, no streaming, no adaptive bitrate, no DRM —
 * ExoPlayer is several megabytes of library to avoid a platform class that does exactly this. Same
 * trade [VideoPlayerScreen] made.
 *
 * ### The scrubber
 *
 * A bar you drag, and it seeks while you drag rather than when you let go. Seeking on release is
 * cheaper and it is the wrong feel: you cannot find a word in a recording you cannot hear until you
 * have finished looking for it. `MediaPlayer.seekTo` is fast enough on a local file to do it live.
 *
 * The position is polled rather than observed, because `MediaPlayer` has no position callback. Ten
 * times a second, and only while it is playing — a paused player is not asking for frames.
 */
@Composable
fun AudioPlayerScreen(
    file: File,
    name: String?,
    onClose: () -> Unit,
    transcript: String? = null,
    canTranscribe: Boolean = false,
    onTranscribe: (() -> Unit)? = null,
) {
    BackHandler(onBack = onClose)

    var failed by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var position by remember(file) { mutableIntStateOf(0) }
    var duration by remember(file) { mutableIntStateOf(0) }

    val player = remember(file) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
            }
        }.getOrElse {
            failed = true
            null
        }
    }

    DisposableEffect(player) {
        player?.let {
            duration = it.duration.coerceAtLeast(0)
            // Autoplay: you tapped a sound to hear it, and a screen that opens with a stopped
            // player asks you to say so twice.
            runCatching { it.start(); playing = true }
            it.setOnCompletionListener { _ ->
                playing = false
                position = duration
            }
        }
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
        }
    }

    // Polled, because MediaPlayer has no position callback. Only while playing.
    LaunchedEffect(player, playing) {
        val p = player ?: return@LaunchedEffect
        while (playing) {
            position = runCatching { p.currentPosition }.getOrDefault(position)
            delay(100)
        }
    }

    Surface(Modifier.fillMaxSize(), color = ChatColors.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (failed || player == null) {
                Text(
                    "This sound won’t play here",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth()) {
                    PlayerKey("CLOSE", Modifier.weight(1f), onClick = onClose)
                }
                return@Column
            }

            Text(
                name?.takeIf { it.isNotBlank() } ?: "Audio",
                style = ChatType.body,
                color = ChatColors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))

            Scrubber(
                fraction = if (duration > 0) position.toFloat() / duration else 0f,
                onSeek = { fraction ->
                    val to = (fraction * duration).toInt().coerceIn(0, duration)
                    position = to
                    runCatching { player.seekTo(to) }
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(clock(position), style = ChatType.meta, color = ChatColors.onSurfaceDim)
                Spacer(Modifier.weight(1f))
                Text(clock(duration), style = ChatType.meta, color = ChatColors.onSurfaceDim)
            }

            Spacer(Modifier.height(28.dp))
            Row(Modifier.fillMaxWidth()) {
                PlayerKey("<< 10", Modifier.weight(1f)) {
                    val to = (position - 10_000).coerceAtLeast(0)
                    position = to
                    runCatching { player.seekTo(to) }
                }
                PlayerKey(if (playing) "PAUSE" else "PLAY", Modifier.weight(1f), held = playing) {
                    if (playing) {
                        runCatching { player.pause() }
                        playing = false
                    } else {
                        // Starting from the end would play nothing at all, so it starts again.
                        if (duration > 0 && position >= duration - 50) {
                            runCatching { player.seekTo(0) }
                            position = 0
                        }
                        runCatching { player.start() }
                        playing = true
                    }
                }
                PlayerKey("10 >>", Modifier.weight(1f)) {
                    val to = (position + 10_000).coerceAtMost(duration)
                    position = to
                    runCatching { player.seekTo(to) }
                }
            }
            // The words, when there are any. Under the transport rather than over it, because the
            // thing you came here to do is listen; reading is what you do when listening was not
            // enough — a name you did not catch, a street, a number.
            transcript?.takeIf { it.isNotBlank() }?.let { words ->
                Spacer(Modifier.height(20.dp))
                Text(
                    words,
                    style = ChatType.body,
                    color = ChatColors.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                if (canTranscribe && transcript == null && onTranscribe != null) {
                    PlayerKey("WORDS", Modifier.weight(1f), onClick = onTranscribe)
                }
                PlayerKey("CLOSE", Modifier.weight(1f), onClick = onClose)
            }
        }
    }
}

/**
 * The bar you drag to move through the recording.
 *
 * Deliberately not a Material `Slider`: on this panel one is a thin grey line with a small circle
 * on it, both of which disappear. This is a filled bar the height of a thumb, so where you are is
 * legible at arm's length and anywhere along it is a target.
 *
 * It seeks on the way down as well as while moving, so a tap partway along jumps there without
 * having to drag.
 */
@Composable
private fun Scrubber(fraction: Float, onSeek: (Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(TROUGH)
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val width = size.width.coerceAtLeast(1)
                    onSeek((down.position.x / width).coerceIn(0f, 1f))
                    var stillDown = true
                    while (stillDown) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (change.pressed) {
                                onSeek((change.position.x / width).coerceIn(0f, 1f))
                                change.consume()
                            }
                        }
                        stillDown = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(ChatColors.onSurface),
        )
    }
}

/** A key on the player. Big, square and inverted when it is on, like the rest of this family. */
@Composable
private fun PlayerKey(
    label: String,
    modifier: Modifier = Modifier,
    held: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(52.dp)
            .background(if (held) ChatColors.onSurface else KEY)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    var stillDown = true
                    var cancelled = false
                    while (stillDown) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        stillDown = event.changes.any { it.pressed }
                    }
                    if (!cancelled) onClick()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ChatType.meta,
            color = if (held) ChatColors.background else ChatColors.onSurface,
        )
    }
}

/**
 * The unfilled part of the scrubber, and the face of a key that is not pressed.
 *
 * Dark greys rather than [ChatColors.onSurfaceInactive], which is a text colour: a bar filled with
 * it reads as most of the way through whatever you are listening to.
 */
private val TROUGH = androidx.compose.ui.graphics.Color(0xFF2A2A2A)
private val KEY = androidx.compose.ui.graphics.Color(0xFF161616)

/** Milliseconds as `m:ss`, in US digits so the two ends of the bar line up in every locale. */
private fun clock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
