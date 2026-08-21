package com.gios.lightchat.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ui.theme.ChatColors

/**
 * The dictation key: a microphone, drawn.
 *
 * ### Why it is drawn and not written
 *
 * It has been three things now. `◉` first, which Public Sans has no glyph for, so the key rendered
 * as nothing and the feature was reported missing. Then the word "Speak", which at least could be
 * seen — and which was the report this replaces: it should look like a microphone.
 *
 * A drawn shape rather than a third glyph, and rather than an emoji. There is no font on this phone
 * whose microphone can be relied on: 🎤 is colour-emoji on a monochrome panel where it would be
 * flattened to a grey blob, and U+F4B1-style icon-font characters are exactly the mistake `◉` was.
 * Four primitives — a capsule, an arc, a line and a line — always draw.
 *
 * Filled while it is listening, outlined while it is not. That is the whole state: the same key
 * starts and stops, and the fill is what says which it will do. It is also the one thing on this
 * screen readable at arm's length on a panel with no colour to spend.
 */
@Composable
fun MicKey(listening: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val ink = if (listening) ChatColors.onSurface else ChatColors.onSurfaceDisabled

    Canvas(
        modifier = Modifier
            // Bottom padding, not centring: the row it sits in aligns to the bottom, next to text
            // whose descenders hang below its baseline. Without this the mic floats a couple of
            // pixels low and reads as misaligned.
            .padding(bottom = 3.dp)
            .size(width = 18.dp, height = 22.dp)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            ),
    ) {
        // Proportions of the icon box, not fixed lengths, so it holds together at any size. They
        // were arrived at by rendering the geometry rather than by guessing: the first version put
        // the stem at the *centre* of the cradle arc instead of its lowest point, which drew a
        // trident rather than a microphone.
        val stroke = size.width * 0.10f
        val headWidth = size.width * 0.42f
        val headHeight = size.height * 0.52f
        val headLeft = (size.width - headWidth) / 2f
        val headTop = stroke / 2f
        val headBottom = headTop + headHeight
        val radius = headWidth / 2f

        // The head: a rounded rectangle whose corner radius is half its width, which is a capsule.
        val head = RoundRect(
            Rect(headLeft, headTop, headLeft + headWidth, headBottom),
            CornerRadius(radius, radius),
        )
        val headPath = Path().apply { addRoundRect(head) }
        // Filled while listening, outlined while not. The same key stops what it started, so the
        // fill is the only thing that has to say which of the two the next tap will do.
        if (listening) {
            drawPath(path = headPath, color = ink)
        } else {
            drawPath(path = headPath, color = ink, style = Stroke(width = stroke))
        }

        // The cradle: the bottom half of a circle, whose open ends rise a little way up the sides
        // of the head — which is what makes it read as holding the head rather than sitting under
        // it. Drawn as an arc rather than as two lines and a curve, so there are no joins to align.
        val cradleWidth = size.width * 0.70f
        val cradleCentre = headBottom - headHeight * 0.22f
        drawArc(
            color = ink,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset((size.width - cradleWidth) / 2f, cradleCentre - cradleWidth / 2f),
            size = Size(cradleWidth, cradleWidth),
            style = Stroke(width = stroke),
        )

        // Stem, from the lowest point of the arc, and the foot it stands on.
        val footY = size.height - stroke / 2f
        drawLine(
            color = ink,
            start = Offset(size.width / 2f, cradleCentre + cradleWidth / 2f),
            end = Offset(size.width / 2f, footY),
            strokeWidth = stroke,
        )
        drawLine(
            color = ink,
            start = Offset(size.width * 0.26f, footY),
            end = Offset(size.width * 0.74f, footY),
            strokeWidth = stroke,
        )
    }
}
