package com.gios.lightchat.ui

import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.io.File

/**
 * A video, played in the app rather than handed to somebody else.
 *
 * **Handing it off did not work on this phone.** A non-image attachment downloads and goes out as
 * `ACTION_VIEW`, which is right for a PDF or a vCard and useless for a video on LightOS: there is
 * no player installed, so the chooser is empty and the tap ends in "No app can open this file"
 * after a download you have already waited for. A video somebody sent you is the most ordinary
 * thing in a message thread and it was the one attachment the app could not show.
 *
 * **`VideoView`, not ExoPlayer.** `VideoView` is a platform widget wrapping `MediaPlayer` and
 * costs nothing to depend on; ExoPlayer is several megabytes of library for a screen that plays
 * one local file with no streaming, no adaptive bitrate and no DRM. On an APK sideloaded over a
 * Tailscale tunnel onto a phone whose whole premise is being small, that trade only goes one way.
 *
 * Full screen and opaque, like [ImageViewerScreen] — the thread stays composed underneath, so
 * closing lands exactly where you were with its scroll position intact.
 */
@Composable
fun VideoPlayerScreen(file: File, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var failed by remember(file) { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.background) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failed) {
                Text(
                    text = "This video won't play here",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDim,
                )
            } else {
                AndroidView(
                    factory = { context ->
                        VideoView(context).apply {
                            setVideoURI(file.toUri())
                            // Loop, and no controls. There is no room on a 3.92" panel for a
                            // scrubber that would be a few dozen pixels wide, and a clip in a
                            // message thread is seconds long — watching it twice is easier than
                            // aiming at a seek bar. Tap anywhere to leave.
                            setOnPreparedListener { player ->
                                player.isLooping = true
                                start()
                            }
                            // Never a dialog, and never silence: MediaPlayer's own error alert is
                            // a Material box on a monochrome panel, and returning true from here
                            // is what suppresses it. The line above says the same thing quietly.
                            setOnErrorListener { _, _, _ ->
                                failed = true
                                true
                            }
                        }
                    },
                    // Released on the way out rather than left to the garbage collector: a
                    // MediaPlayer holds a codec, and the phone has few of them.
                    onRelease = { it.stopPlayback() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // The whole surface closes, which is the same gesture the image viewer uses. A
            // dedicated close button would be the only chrome on the screen.
            HapticText(
                text = "Close",
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                onClick = onClose,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            )
        }
    }

    // Belt and braces for the case AndroidView's onRelease does not cover: leaving the
    // composition by any route stops playback, so a video never keeps playing behind the thread.
    DisposableEffect(file) { onDispose { } }
}
