package com.gios.lightchat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType

/**
 * The box itself. Solid black with a hairline outline, because it lands on top of
 * pixels we don't own — a borderless black panel over another black app would have
 * no edge at all, and the panel is greyscale so a tint can't provide one.
 *
 * Two lines of message, ellipsised. The point is to know whether it's worth picking
 * the phone up, not to read the message here.
 */
/** How far up you have to drag to throw the box away. Roughly a third of its own
 *  height — far enough not to trigger on a sloppy tap. */
private const val DISMISS_TRAVEL_PX = 40f

@Composable
fun HeadsUpBox(
    title: String,
    text: String,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // Clear of the status-bar strip: the window is FLAG_LAYOUT_NO_LIMITS so
            // it can draw up there, and this is what keeps the text out of it.
            .padding(start = 12.dp, end = 12.dp, top = 36.dp, bottom = 12.dp)
            // Square, like everything else in the app — there isn't a rounded corner
            // or a border anywhere else in it, and this one exists only because the
            // box lands on pixels we don't own.
            .background(ChatColors.background)
            .border(1.dp, ChatColors.onSurfaceDim)
            // Swipe up to get rid of it early — the gesture the shape suggests, and
            // the only control it needs beyond tapping through. Accumulated, not
            // per-frame: a single frame's dragAmount is a few pixels, so testing it
            // directly would fire on one jittery frame of a downward drag and never
            // fire on a slow deliberate one. Consumed so the drag doesn't also read
            // as a tap.
            .pointerInput(Unit) {
                var travelled = 0f
                detectVerticalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragCancel = { travelled = 0f },
                ) { change, drag ->
                    change.consume()
                    travelled += drag
                    if (travelled < -DISMISS_TRAVEL_PX) onDismiss()
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = ChatType.body,
            color = ChatColors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (text.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = text,
                style = ChatType.meta,
                color = ChatColors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
