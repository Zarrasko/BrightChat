package com.gios.lightchat.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.gios.lightchat.report.Symptom
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import kotlinx.coroutines.delay

/** Why a report is being offered. */
enum class ReportReason { Shaken, Crashed, Failed }

/** A report waiting to be written: the reason, the screenshot taken at the moment, the failure. */
data class ReportRequest(
    val reason: ReportReason,
    val shot: Bitmap?,
    val failure: com.gios.lightchat.report.Failure? = null,
)

/**
 * The corner chip: "SEND ERROR?"
 *
 * **A shake is a gesture the phone can misread**, and a report sheet that appears because you put
 * the phone down hard is the feature being a nuisance. So the shake never opens anything — it
 * offers, in a corner, for a few seconds, and says nothing if ignored. That silence is "not now"
 * rather than "no": an unsent crash log stays on disk for the next launch to ask about again.
 *
 * Ported from Roll, which took it from LightNotebook, deliberately unchanged in behaviour. This
 * is diagnostic UI, not product surface — it should be one learned gesture across every app on
 * the phone rather than four slightly different ones. Only the drawing is rewritten, because
 * LightChat has its own type scale and no `LightThemeTokens`.
 */
@Composable
fun ReportChip(reason: ReportReason, onOpen: () -> Unit, onExpire: () -> Unit) {
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(if (reason == ReportReason.Crashed) SHOWN_CRASH_MS else SHOWN_MS)
        shown = false
    }
    // Torn down only after the fade has played, or it would vanish rather than fade — which on
    // this panel reads as a glitch, from the feature whose whole job is glitches.
    LaunchedEffect(shown) {
        if (shown) return@LaunchedEffect
        delay(FADE_MS)
        onExpire()
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "chip")
    HapticText(
        text = when (reason) {
            ReportReason.Crashed -> "SEND CRASH?"
            else -> "SEND ERROR?"
        },
        style = ChatType.hint,
        color = ChatColors.onSurface,
        onClick = onOpen,
        modifier = Modifier
            .alpha(alpha)
            .background(ChatColors.background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private const val SHOWN_MS = 4_000L
private const val SHOWN_CRASH_MS = 8_000L
private const val FADE_MS = 400L

/**
 * What went wrong, once you have said you want to tell somebody.
 *
 * Full screen rather than a dialog, like every other panel here. By the time this is up the
 * answer to "did you mean to report something" is already yes — the chip asked it — so this gets
 * straight to the part that carries information: which of five things happened, a line in your
 * own words, and whether to attach the screenshot taken at the moment of the shake.
 *
 * The screenshot is opt-out rather than opt-in. A picture of the broken screen is the single most
 * useful thing in a report and nobody remembers to tick a box for it; the row says plainly that
 * it is going, so anybody who does not want it can say so.
 */
@Composable
fun ReportSheet(
    reason: ReportReason,
    hasScreenshot: Boolean,
    failure: String? = null,
    seedNote: String = "",
    onDismiss: () -> Unit,
    onSend: (symptom: Symptom, note: String, includeScreenshot: Boolean) -> Unit,
) {
    var symptom by remember {
        mutableStateOf(if (reason == ReportReason.Crashed) Symptom.Crashed else Symptom.Other)
    }
    var note by remember { mutableStateOf(seedNote) }
    var withShot by remember { mutableStateOf(hasScreenshot) }

    Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.background) {
        Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HapticText(
                    text = "‹",
                    style = ChatType.title,
                    color = ChatColors.onSurface,
                    onClick = onDismiss,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "What happened?",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(24.dp))
            }

            if (failure != null) {
                Text(
                    text = "LightChat could not $failure",
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDim,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Column(modifier = Modifier.padding(top = 16.dp)) {
                for (option in Symptom.entries) {
                    HapticText(
                        text = (if (option == symptom) "• " else "  ") + option.label,
                        style = ChatType.body,
                        color = if (option == symptom) ChatColors.onSurface else ChatColors.onSurfaceDim,
                        onClick = { symptom = option },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }

            // A line in your own words, and the field that makes a report worth reading. The
            // underline is the whole of the decoration: Material's filled box appears nowhere on
            // this phone.
            Column(modifier = Modifier.padding(top = 16.dp)) {
                if (note.isEmpty()) {
                    Text(
                        text = "Anything to add?",
                        style = ChatType.body,
                        color = ChatColors.onSurfaceDim,
                    )
                }
                BasicTextField(
                    value = note,
                    onValueChange = { note = it },
                    textStyle = ChatType.body.copy(color = ChatColors.onSurface),
                    cursorBrush = SolidColor(ChatColors.onSurface),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (hasScreenshot) {
                HapticText(
                    text = if (withShot) "Screenshot attached" else "Screenshot left out",
                    style = ChatType.hint,
                    color = if (withShot) ChatColors.onSurface else ChatColors.onSurfaceDim,
                    onClick = { withShot = !withShot },
                    modifier = Modifier.padding(top = 20.dp),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                HapticText(
                    text = "Cancel",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDim,
                    onClick = onDismiss,
                )
                HapticText(
                    text = "Send",
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    onClick = { onSend(symptom, note, withShot) },
                )
            }
        }
    }
}
