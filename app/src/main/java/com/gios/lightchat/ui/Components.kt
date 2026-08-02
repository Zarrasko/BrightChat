@file:OptIn(ExperimentalFoundationApi::class)

package com.gios.lightchat.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType
import java.text.DateFormat
import java.util.Date

/** Tappable text with a haptic tick on press — the vandamd "button". */
@Composable
fun HapticText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    underline: Boolean = false,
    textAlign: TextAlign = TextAlign.Center,
    maxLines: Int = Int.MAX_VALUE,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Text(
        text = text,
        style = style,
        color = color,
        textAlign = textAlign,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textDecoration = if (underline) TextDecoration.Underline else TextDecoration.None,
        modifier = modifier.combinedClickable(
            interactionSource = interaction,
            indication = null,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            onLongClick = onLongClick?.let {
                {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    it()
                }
            },
        ),
    )
}

/** Top row shared by the sub-screens: a back chevron on the left and a centred
 *  title (the trailing spacer balances the chevron so the title sits centred).
 *  A non-null [onTitleClick] makes the title itself tappable (the thread uses
 *  this to open the chat's details). */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    /**
     * An action on the right, in place of the spacer that balances the back chevron.
     *
     * Optional and nullable rather than a default empty lambda, because the spacer is
     * load-bearing: it is what keeps the title on the centre line instead of being pushed off
     * it by an icon on one side only. A caller that supplies something is responsible for it
     * being about the same width, which for a short word it is.
     */
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HapticText(
            text = "‹",
            style = ChatType.title,
            color = ChatColors.onSurface,
            onClick = onBack,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onTitleClick != null) {
            HapticText(
                text = title,
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                onClick = onTitleClick,
            )
        } else {
            Text(
                text = title,
                style = ChatType.body,
                color = ChatColors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (trailing != null) trailing() else Spacer(modifier = Modifier.width(24.dp))
    }
}

/**
 * A timestamp in a list, iMessage-style: a clock time for today ("3:14 PM"), "Yesterday",
 * the weekday within the last week ("Monday"), then a short date ("7/1/26"). Absolute past
 * a day — "18 hours ago" stops being parseable.
 *
 * Shared by the conversation list and the contact page's links, so the two cannot drift
 * into telling the time differently on the same screenful. Blank for an unknown date, which
 * callers must not concatenate a separator onto.
 */
internal fun listTime(context: Context, ts: Long): String {
    if (ts <= 0L) return ""
    if (DateUtils.isToday(ts)) return DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_TIME)
    if (DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS)) return "Yesterday"
    if (System.currentTimeMillis() - ts < 7 * DateUtils.DAY_IN_MILLIS) {
        return DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_WEEKDAY)
    }
    return DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts))
}
