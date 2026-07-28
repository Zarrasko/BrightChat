@file:OptIn(ExperimentalFoundationApi::class)

package com.craigeley.chat.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.craigeley.chat.MediaRescan
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatType
import kotlinx.coroutines.launch

/**
 * The system photo picker, with a MediaStore nudge first. Nothing on this phone
 * keeps MediaStore's index fresh, so launched bare the picker only shows
 * long-ago-indexed images (see [MediaRescan]); instead the returned lambda —
 * wire it to the compose bar's "+" — rescans anything new in DCIM/Pictures and
 * *then* launches. The rescan needs READ_MEDIA_IMAGES (the picker itself needs
 * no permission), asked once on first use; a denial just skips the rescan and
 * opens the picker anyway, stale but functional.
 */
@Composable
fun rememberFreshImagePicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) picked(uri)
    }
    val rescanThenPick: () -> Unit = {
        scope.launch {
            MediaRescan.rescan(context) // no-op without the permission
            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> rescanThenPick() } // granted or denied, the picker still opens
    return {
        if (MediaRescan.hasPermission(context)) {
            rescanThenPick()
        } else {
            askPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }
}

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
        Spacer(modifier = Modifier.width(24.dp))
    }
}
