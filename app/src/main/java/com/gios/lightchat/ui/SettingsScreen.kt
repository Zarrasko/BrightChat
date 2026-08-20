package com.gios.lightchat.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.collectAsState
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
import com.gios.lightchat.CallAnnounce
import com.gios.lightchat.ChatViewModel
import com.gios.lightchat.Delivery
import com.gios.lightchat.Dialer
import com.gios.lightchat.api.Store
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatDimens
import com.gios.lightchat.ui.theme.ChatType

@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
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

        Text(text = "Agents", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        Spacer(modifier = Modifier.height(16.dp))
        state.agents.forEach { agent ->
            HapticText(
                text = agent.name,
                style = ChatType.body,
                color = ChatColors.onSurface,
                onClick = { viewModel.openEditAgent(agent) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        HapticText(
            text = "Add agent",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            onClick = { viewModel.openNewAgent() },
            modifier = Modifier.fillMaxWidth(),
        )

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

        // Off by default: an old iMessage account gets a steady trickle from short codes,
        // delivery notices and two-factor senders, and on this phone every one of them buzzes,
        // wakes the panel and puts a box in front of what you were doing. Filtered messages still
        // arrive and still carry an unread mark — they just don't interrupt.
        var notifyUnknown by remember { mutableStateOf(Store.notifyUnknown(context)) }
        HapticText(
            text = if (notifyUnknown) "Unknown senders: notify" else "Unknown senders: silent",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            textAlign = TextAlign.Center,
            onClick = {
                notifyUnknown = !notifyUnknown
                Store.setNotifyUnknown(context, notifyUnknown)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (notifyUnknown) {
                "Anyone can buzz this phone."
            } else {
                "Only your contacts and named groups buzz. The rest still arrive, quietly."
            },
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        Spacer(modifier = Modifier.height(20.dp))

        // On by default. Off keeps the buzz and the shade notification but never puts
        // the box over the screen (or wakes it) — for people who find a lit-up panel
        // worse than waiting to check.
        var headsUpBox by remember { mutableStateOf(Store.headsUpBox(context)) }
        HapticText(
            text = if (headsUpBox) "On-screen alerts: on" else "On-screen alerts: off",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            textAlign = TextAlign.Center,
            onClick = {
                headsUpBox = !headsUpBox
                Store.setHeadsUpBox(context, headsUpBox)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (headsUpBox) {
                "New messages put a box over whatever the phone is showing."
            } else {
                "Just the buzz and a notification. Nothing appears over the screen."
            },
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Off by default, and only shown on a phone that can place a call at all. When
        // on, placing a call also iMessages the callee which number is ringing them —
        // the SIM's number, not the iMessage one — so the call-back comes to this phone.
        if (Dialer.available(context)) {
            var callAnnounce by remember { mutableStateOf(Store.callAnnounce(context)) }
            // The SIM's own number needs READ_PHONE_NUMBERS; asked for at the moment the
            // toggle goes on, which is the moment the answer starts mattering. A refusal
            // is not a dead end — the number can be typed in below, and the message has
            // a wording for having no number at all.
            var ownNumber by remember { mutableStateOf(CallAnnounce.ownNumber(context)) }
            val askNumber = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { ownNumber = CallAnnounce.ownNumber(context) }
            HapticText(
                text = if (callAnnounce) "Announce calls: on" else "Announce calls: off",
                style = ChatType.body,
                color = ChatColors.onSurfaceDim,
                textAlign = TextAlign.Center,
                onClick = {
                    callAnnounce = !callAnnounce
                    Store.setCallAnnounce(context, callAnnounce)
                    if (callAnnounce && ownNumber == null) {
                        askNumber.launch(Manifest.permission.READ_PHONE_NUMBERS)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = if (callAnnounce) {
                    "Calling someone also texts them: \u201c" +
                        CallAnnounce.messageText(ownNumber) + "\u201d"
                } else {
                    "Calls are just calls."
                },
                style = ChatType.hint,
                color = ChatColors.onSurfaceDisabled,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (callAnnounce) {
                Spacer(modifier = Modifier.height(8.dp))
                // The number the text names, editable because plenty of SIMs don't know
                // their own number. Prefilled from the SIM when it does.
                var editingNumber by remember { mutableStateOf(false) }
                var draftNumber by remember { mutableStateOf(Store.myNumber(context) ?: ownNumber.orEmpty()) }
                if (editingNumber) {
                    BasicTextField(
                        value = draftNumber,
                        onValueChange = { draftNumber = it },
                        singleLine = true,
                        textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(ChatColors.onSurface),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            Store.setMyNumber(context, draftNumber)
                            ownNumber = CallAnnounce.ownNumber(context)
                            editingNumber = false
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    HapticText(
                        text = ownNumber?.let { "Your number: " + CallAnnounce.prettyUs(it) }
                            ?: "Tap to set your number",
                        style = ChatType.hint,
                        color = ChatColors.onSurfaceDim,
                        textAlign = TextAlign.Center,
                        onClick = {
                            draftNumber = Store.myNumber(context) ?: ownNumber.orEmpty()
                            editingNumber = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        // ------------------------------------------------------------ transcription
        //
        // A URL and nothing else, because there is nothing to switch on: no model ships in this
        // app, so transcription exists exactly to the extent that this points at a server. Anything
        // answering OpenAI's `/v1/audio/transcriptions` will do — whisper.cpp's own server, a
        // faster-whisper box, LM Studio, or OpenAI itself.
        var whisperUrl by remember { mutableStateOf(Store.whisperUrl(context).orEmpty()) }
        var whisperKey by remember { mutableStateOf(Store.whisperKey(context)) }
        var whisperModel by remember { mutableStateOf(Store.whisperModel(context)) }
        var editingWhisper by remember { mutableStateOf(false) }

        HapticText(
            text = if (whisperUrl.isBlank()) {
                "Transcription: off"
            } else {
                "Transcription: $whisperModel"
            },
            style = ChatType.body,
            color = if (whisperUrl.isBlank()) ChatColors.onSurfaceDim else ChatColors.onSurface,
            onClick = { editingWhisper = !editingWhisper },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = if (whisperUrl.isBlank()) {
                "Point this at a Whisper server and a voice memo can be read as well as heard."
            } else {
                whisperUrl
            },
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        if (editingWhisper) {
            Spacer(modifier = Modifier.height(10.dp))
            WhisperField(
                value = whisperUrl,
                hint = "https://your-server:9000",
                onDone = {
                    Store.setWhisperUrl(context, it)
                    whisperUrl = Store.whisperUrl(context).orEmpty()
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
            WhisperField(
                value = whisperKey,
                hint = "API key, if it needs one",
                onDone = {
                    Store.setWhisperKey(context, it)
                    whisperKey = Store.whisperKey(context)
                },
            )
            Spacer(modifier = Modifier.height(6.dp))
            WhisperField(
                value = whisperModel,
                hint = "whisper-1",
                onDone = {
                    Store.setWhisperModel(context, it)
                    whisperModel = Store.whisperModel(context)
                },
            )
        }

        Spacer(modifier = Modifier.height(36.dp))

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

/**
 * One line of the transcription setup.
 *
 * Three near-identical fields rather than one screen, because they are three unrelated strings and
 * a form on this panel is worse than three lines. Each commits on Done, so nothing is half-saved if
 * you leave.
 */
@Composable
private fun WhisperField(value: String, hint: String, onDone: (String) -> Unit) {
    var draft by remember(value) { mutableStateOf(value) }
    BasicTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(ChatColors.onSurface),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(draft) }),
        decorationBox = { inner ->
            if (draft.isEmpty()) {
                Text(
                    hint,
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDisabled,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            inner()
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
