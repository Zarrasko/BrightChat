package com.gios.lightchat.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.gios.lightchat.Dialer
import com.gios.lightchat.api.Store
import com.gios.lightchat.dial.AddressBook
import com.gios.lightchat.dial.AddressBookRepo
import com.gios.lightchat.dial.PhoneContact
import com.gios.lightchat.dial.T9
import com.gios.lightchat.hw.WheelScroll
import com.gios.lightchat.ui.theme.ChatColors
import com.gios.lightchat.ui.theme.ChatType

/**
 * The dialer, which is also the contacts list, because on a phone they are one screen.
 *
 * **One field with two meanings and no mode switch.** The pad is asking "ring this number" and
 * "find this person" at the same time, and the digits answer both: `442` narrows the list to
 * everyone whose name starts a word with G-I-A and everyone whose number contains 442, in that
 * order. Nothing has to be told which you meant. At rest, with nothing typed, it is simply the
 * address book — which is why there is no separate contacts screen to switch to.
 *
 * **Press and hold a key from 1 to 9 to speed dial.** An empty slot takes whoever is at the top
 * of the list, so assigning is the same gesture as using, one step earlier: search for somebody,
 * hold a free key, and from then on that key rings them from an empty pad. No assignment screen,
 * no picker — both would be more UI than the feature is worth.
 *
 * Calling itself is [Dialer], unchanged from the thread header: `TelecomManager.placeCall`, then
 * this app steps aside to the home screen so the call has the foreground.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DialerScreen(
    tab: com.gios.lightchat.ui.ConversationTab,
    onSelectTab: (com.gios.lightchat.ui.ConversationTab) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val ring = rememberCaller()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    // Granting happens outside this app, so the answer is re-read on the way back rather than
    // waiting for another tap.
    LifecycleResumeEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    var all by remember { mutableStateOf<List<PhoneContact>?>(null) }
    LaunchedEffect(granted) {
        if (granted) all = AddressBookRepo(context).load()
    }

    var digits by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    // The notice is a sentence about something that just happened; it should not still be there
    // a minute later claiming it.
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        notice = null
    }

    val loaded = all.orEmpty()
    val results = remember(loaded, digits) { AddressBook.search(loaded, digits) }
    val listState = rememberLazyListState()
    WheelScroll(listState)

    /**
     * A key held down. Speed dial, and assignment, and the explanation of both.
     *
     * An occupied slot rings. An empty one takes the top result, which is what makes assigning
     * discoverable — you were already looking at the person you meant.
     */
    val onHold: (Char) -> Unit = hold@{ key ->
        // The one hold that isn't speed dial. Holding 0 for + is older than speed dial and is
        // the only way to type an E.164 number on a pad that has no plus key.
        if (key == '0') {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            digits += "+"
            return@hold
        }
        if (key !in '1'..'9') return@hold
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        val slot = key - '0'
        val held = Store.speedDial(context, slot)
        if (held != null) {
            digits = ""
            ring(held.number)
            return@hold
        }
        val top = results.firstOrNull()?.primary
        val name = results.firstOrNull()?.name
        if (top == null || name == null) {
            notice = "Nothing to put on $slot yet — search for somebody first"
            return@hold
        }
        Store.setSpeedDial(context, slot, top.raw, name)
        notice = "$name on $slot"
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // No title. The pad below says what this screen is more plainly than a word could,
            // and on a 3.92" panel a header that only labels is a row of contacts given up.
            Spacer(modifier = Modifier.weight(1f))
            if (digits.isNotEmpty()) {
                HapticText(
                    text = "Clear",
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDim,
                    onClick = { digits = "" },
                )
            }
        }

        when {
            !granted -> Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Who are you calling?",
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "The pad searches your contacts as you type, so it needs to read them. " +
                        "They are read on this phone and nothing is sent anywhere.",
                    style = ChatType.hint,
                    color = ChatColors.onSurfaceDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
                HapticText(
                    text = "Allow",
                    style = ChatType.body,
                    color = ChatColors.onSurface,
                    onClick = { ask.launch(Manifest.permission.READ_CONTACTS) },
                    modifier = Modifier.padding(top = 24.dp),
                )
            }

            else -> {
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        // **A number nobody has saved is still a number.** Above the results
                        // rather than below, because once enough digits are typed to be a real
                        // number this is usually the row that was meant.
                        if (digits.length >= T9.DIAL_ROW_AFTER) {
                            item(key = "dial-raw") {
                                DialRow(
                                    title = "Call $digits",
                                    subtitle = "Dial this number",
                                    onClick = { ring(digits) },
                                )
                            }
                        }
                        // **Nothing until you type.** The address book in full is a list nobody
                        // scrolls to find somebody in — that is what the pad is for — and putting
                        // it on screen at rest means every glance at this tab starts with the
                        // wrong three hundred people. Typing one digit is enough to make the list
                        // worth having.
                        items(if (digits.isEmpty()) emptyList() else results, key = { it.id }) { contact ->
                            DialRow(
                                title = contact.name,
                                subtitle = contact.subtitle,
                                onClick = { contact.primary?.let { ring(it.raw) } },
                            )
                        }
                        if (digits.isNotEmpty() && results.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = "Nobody matches that.",
                                    style = ChatType.hint,
                                    color = ChatColors.onSurfaceDim,
                                    modifier = Modifier.padding(vertical = 24.dp),
                                )
                            }
                        }
                    }
                }

                notice?.let {
                    Text(
                        text = it,
                        style = ChatType.hint,
                        color = ChatColors.onSurfaceDim,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }

                Keypad(
                    digits = digits,
                    onKey = { digits += it },
                    onHold = onHold,
                    onBackspace = { digits = digits.dropLast(1) },
                )
            }
        }

        ConversationNavbar(current = tab, unread = emptySet(), onSelect = onSelectTab)
    }
}

/** One person, or one raw number. Name over number, the same list row as everywhere else. */
@Composable
private fun DialRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        HapticText(
            text = title,
            style = ChatType.body,
            color = ChatColors.onSurface,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = ChatType.hint,
                color = ChatColors.onSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The pad.
 *
 * Twelve keys in four rows, the arrangement every phone has had since it stopped being a dial.
 * The letters under each digit are drawn because they are the only thing that explains why
 * typing `442` found Gia — without them T9 is a coincidence rather than a feature.
 *
 * The typed digits sit above the pad rather than in a text field: there is no cursor to place
 * and nothing to select, and a `BasicTextField` here would summon the software keyboard over the
 * pad the moment it took focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Keypad(
    digits: String,
    onKey: (Char) -> Unit,
    onHold: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = digits,
                style = ChatType.body,
                color = ChatColors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (digits.isNotEmpty()) {
                HapticText(
                    text = "⌫",
                    style = ChatType.body,
                    color = ChatColors.onSurfaceDim,
                    onClick = onBackspace,
                )
            }
        }
        for (row in KEY_ROWS) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (key in row) {
                    Key(key = key, onKey = onKey, onHold = onHold, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Key(
    key: Char,
    onKey: (Char) -> Unit,
    onHold: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onKey(key)
                },
                onLongClick = { onHold(key) },
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = key.toString(), style = ChatType.body, color = ChatColors.onSurface)
        val letters = LETTERS[key]
        if (letters != null) {
            Text(text = letters, style = ChatType.hint, color = ChatColors.onSurfaceDim)
        } else {
            Spacer(modifier = Modifier.height(2.dp).width(1.dp))
        }
    }
}

private val KEY_ROWS = listOf("123", "456", "789", "*0#")

private val LETTERS = mapOf(
    '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL",
    '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ",
    '0' to "+",
)
