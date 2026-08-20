package com.gios.lightchat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Speaking a message instead of typing one.
 *
 * The keyboard on this phone is the hardest part of using it, and the app is already configured to
 * talk to a Whisper server for the sake of reading somebody else's voice memo. The same endpoint
 * turns your own voice into a message, which is the more useful direction of the two: you read a
 * memo occasionally and you type every day.
 *
 * ### What it records
 *
 * AAC in an MP4 container — `.m4a` — at 16 kHz mono. Not a taste:
 *
 *  - **16 kHz** is what Whisper resamples everything to internally, so recording higher only makes a
 *    bigger file for the same transcript.
 *  - **Mono**, for the same reason twice over.
 *  - **AAC and not WAV**, because this file crosses a network. A minute of speech is about 120 kB
 *    compressed against 1.9 MB uncompressed, and on a phone tethered over a tunnel that is the
 *    difference between a pause and a wait.
 *
 * ### One recorder, and it is always released
 *
 * `MediaRecorder` holds a hardware encoder, and this phone has few. Every path out of [stop] and
 * [cancel] releases it, including the failure paths — a recorder leaked by an exception is a
 * microphone no other app can open until the process dies.
 */
class Dictation(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null

    val isRecording: Boolean get() = recorder != null

    fun granted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Begin. False if the microphone could not be opened, which the caller should say out loud. */
    fun start(): Boolean {
        if (recorder != null) return true
        if (!granted()) return false
        val target = File(dir(), "dictation-${System.nanoTime()}.m4a")
        return runCatching {
            @Suppress("DEPRECATION")
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            rec.apply {
                // VOICE_RECOGNITION rather than MIC: it is the source the platform points at speech,
                // with the noise suppression and gain a phone applies for exactly this, and it is
                // what a transcription model wants handed to it.
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(target.path)
                prepare()
                start()
            }
            recorder = rec
            file = target
            true
        }.getOrElse {
            release()
            target.delete()
            false
        }
    }

    /**
     * Finish, and hand back the file to be transcribed. Null if nothing usable was captured.
     *
     * A recording shorter than [MIN_BYTES] is a key pressed by accident: an MP4 header and no audio.
     * Transcribing one costs a round trip to be told nothing was said.
     */
    fun stop(): File? {
        val rec = recorder ?: return null
        val target = file
        runCatching { rec.stop() }
        release()
        if (target == null || !target.isFile || target.length() < MIN_BYTES) {
            target?.delete()
            return null
        }
        return target
    }

    /** Throw it away — the thumb came off somewhere it should not have, or the screen went away. */
    fun cancel() {
        val rec = recorder ?: return
        // `stop` on a recorder that never got going throws, and it is being discarded anyway.
        runCatching { rec.stop() }
        release()
        file?.delete()
        file = null
    }

    /** Anything left behind by a process that died mid-dictation. */
    fun sweep() {
        runCatching { dir().listFiles()?.forEach { it.delete() } }
    }

    private fun release() {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
    }

    private fun dir(): File = File(context.cacheDir, "dictation").apply { mkdirs() }

    private companion object {
        /** What Whisper resamples to anyway. Recording higher is a bigger file for the same words. */
        const val SAMPLE_RATE = 16_000

        /** Speech at 16 kHz mono. Generous for the band it has to carry, and still tiny. */
        const val BIT_RATE = 24_000

        /** Below this there is a container header and nothing in it. */
        const val MIN_BYTES = 2_000L
    }
}
