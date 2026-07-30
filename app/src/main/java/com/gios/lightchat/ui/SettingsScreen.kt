package com.gios.lightchat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Delivery
import com.gios.lightchat.api.Store
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatDimens
import com.gios.lightchat.ui.theme.ChatType

@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentUrl = Store.baseUrl(context).orEmpty()
    var editing by remember { mutableStateOf(false) }
    var draftUrl by remember { mutableStateOf(currentUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ChatDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        Spacer(modifier = Modifier.weight(1f))

        Text(text = "Server", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        Spacer(modifier = Modifier.height(16.dp))
        if (editing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = draftUrl,
                    onValueChange = { draftUrl = it },
                    singleLine = true,
                    textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(ChatColors.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (draftUrl.isNotBlank()) viewModel.updateServerUrl(draftUrl)
                        editing = false
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
            }
        } else {
            HapticText(
                text = currentUrl.removePrefix("https://").removePrefix("http://").ifEmpty { "Tap to set" },
                style = ChatType.body,
                color = ChatColors.onSurface,
                textAlign = TextAlign.Center,
                onClick = {
                    draftUrl = currentUrl
                    editing = true
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

        // Stays on this screen rather than going back, so the outcome can actually be
        // read — including the case where the Private API is off and the Mac still shows
        // everything unread.
        var readResult by remember { mutableStateOf<String?>(null) }
        HapticText(
            text = "Mark all as read",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            onClick = { readResult = viewModel.markAllRead() },
            modifier = Modifier.fillMaxWidth(),
        )
        readResult?.let {
            Text(
                text = it,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        HapticText(
            text = "Refresh conversations",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            onClick = {
                viewModel.refresh()
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(36.dp))

        // Whether the phone is currently letting background delivery happen at all, and
        // when it last did. Both are otherwise unanswerable from the phone: the app can
        // look connected while its poll is being deferred for hours by the standby bucket,
        // and an alarm chain that stopped firing overnight leaves no other trace. Read on
        // each visit rather than remembered — the whole value is that it's current.
        val health = Delivery.healthLines(context)
        Text(
            text = health.first,
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = health.second,
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (!Delivery.isExempt(context)) {
            // LightOS ships almost no Settings UI, so this dialog usually doesn't exist —
            // the tap is offered when it resolves and the adb command named otherwise,
            // rather than showing a control that silently does nothing.
            val intent = Delivery.exemptionIntent(context)
            if (intent != null) {
                HapticText(
                    text = "Allow background delivery",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDim,
                    textAlign = TextAlign.Center,
                    onClick = { runCatching { context.startActivity(intent) } },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            } else {
                Text(
                    text = "Fix over adb: dumpsys deviceidle whitelist +" + context.packageName,
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        HapticText(
            text = "Sign out",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            textAlign = TextAlign.Center,
            onClick = { viewModel.signOut() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
