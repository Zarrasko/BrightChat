package com.gios.lightchat.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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
    /** True while it is listening, so the key can say Stop. */
    val listening: Boolean,
    /** Tap. Starts, or stops and transcribes, handing the words to [onWords]. */
    val onTap: (onWords: (String) -> Unit) -> Unit,
)

/**
 * Wire up dictation for a screen.
 *
 * ### The key is always there
 *
 * It used to be hidden unless a transcription server was configured, on the reasoning that a key
 * which cannot work is worse than no key. That was wrong, and the report was "no mic button": a
 * hidden key teaches nobody anything, and the person most likely to be missing the setting is the
 * person who just asked for the feature. It is shown, and pressing it with nothing configured says
 * what to do about it.
 *
 * The setting is read **when the key is pressed**, not when the screen is composed. Reading it at
 * composition meant that setting a server and coming back to a thread that never left composition
 * left the key still believing there was none.
 */
@Composable
fun rememberDictation(viewModel: ChatViewModel): DictationControl {
    val context = LocalContext.current
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
                // Read now rather than at composition, and said out loud rather than hidden.
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
