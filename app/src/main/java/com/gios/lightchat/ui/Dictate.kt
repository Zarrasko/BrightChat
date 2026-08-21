package com.gios.lightchat.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Dictation
import com.gios.lightchat.api.Store

/**
 * The Speak key's behaviour, in one place, for every screen that composes a message.
 *
 * It was in the thread only, which was most of why it could not be found: a new message and an agent
 * thread are both places you type, and neither had it. Extracted rather than copied three times,
 * because the part that must not diverge is releasing the recorder.
 */
class DictationControl(
    /**
     * Whether to show the key at all.
     *
     * False until a transcription server is configured. This was the other way round for one
     * release — always shown, because a hidden key had been reported as a missing one — and the
     * answer to that turned out to be a settings page you can actually reach rather than a key that
     * cannot work. A microphone that only ever apologises is worse than no microphone.
     */
    val available: Boolean,
    /** True while it is listening, so the key can be filled in. */
    val listening: Boolean,
    /** Tap. Starts, or stops and transcribes, handing the words to [onWords]. */
    val onTap: (onWords: (String) -> Unit) -> Unit,
)

/**
 * Wire up dictation for a screen.
 *
 * ### When the key is there
 *
 * Only once a transcription server is configured — [DictationControl.available]. It went the other
 * way for one release, on the reasoning that a hidden key teaches nobody anything; what that missed
 * is that a key which cannot work teaches them the wrong thing, and the real fix for "I could not
 * find the setting" was a settings page you can reach.
 *
 * Whether it is configured comes from [ChatViewModel]'s state, not from the store as each screen
 * composes. That is what makes it appear the moment the setting is filled in: reading it at
 * composition meant setting a server and coming back to a thread that never left composition left
 * the key still believing there was none.
 */
@Composable
fun rememberDictation(viewModel: ChatViewModel): DictationControl {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val dictation = remember { Dictation(context) }
    var listening by remember { mutableStateOf(false) }

    val askMicrophone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            listening = dictation.start()
            if (!listening) viewModel.say("Couldn't open the microphone")
        } else {
            viewModel.say("Dictation needs the microphone")
        }
    }

    // Speaking is the one thing you do on this phone with nothing to touch, so the display timeout
    // has no idea you are still there and the panel goes dark mid-sentence. The recording itself
    // survives that — MediaRecorder does not care about the screen — but you are then talking at a
    // black phone with no way to tell whether it is still listening, and the app is a background
    // process that can be reclaimed with the take unsaved.
    //
    // Held only while the microphone is open. The transcription that follows is held from
    // MainActivity instead, which sees every Whisper request rather than only this one.
    KeepScreenOn(listening)

    // A recorder holds a hardware encoder and this phone has few, so leaving the screen releases it
    // whatever state it was in — including a dictation still running.
    DisposableEffect(dictation) {
        dictation.sweep()
        onDispose {
            dictation.cancel()
            listening = false
        }
    }

    return DictationControl(
        available = state.canTranscribe,
        listening = listening,
        onTap = { onWords ->
            when {
                dictation.isRecording -> {
                    val recorded = dictation.stop()
                    listening = false
                    if (recorded == null) {
                        viewModel.say("Didn't catch that")
                    } else {
                        viewModel.transcribeDictation(recorded) { words ->
                            if (words != null) onWords(words)
                        }
                    }
                }
                // Still checked, and not because the key is reachable without it: the setting can
                // be cleared from the settings screen while a thread underneath holds a stale
                // composition, and a tap that silently recorded into nothing would be worse than
                // this line.
                !Store.canTranscribe(context) ->
                    viewModel.say("Add a transcription server in Settings to dictate")
                dictation.granted() -> {
                    listening = dictation.start()
                    if (!listening) viewModel.say("Couldn't open the microphone")
                }
                else -> askMicrophone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
    )
}
