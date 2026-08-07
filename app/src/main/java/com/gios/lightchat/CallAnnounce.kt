package com.gios.lightchat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.gios.lightchat.api.BlueBubblesApi
import com.gios.lightchat.api.Store
import kotlin.concurrent.thread

/**
 * "I am calling you from my dumb phone" — the automated text that rides along with a call.
 *
 * The problem it solves is caller ID: this phone's SIM has its own number, but the people
 * being called know the iMessage number, so a call from the Light Phone shows up as a
 * number they don't recognise — and gets screened, and the call-back goes to the number
 * that *didn't* ring them. When the toggle is on ([Store.callAnnounce]), placing a call
 * also sends the callee an iMessage naming the number the call is coming from.
 *
 * **Announced when the call is actually placed, not when a keypad opens.** The hook lives
 * on [Dialer.call]'s success paths only; the `ACTION_DIAL` fallback merely shows a keypad,
 * and texting "I am calling you" about a call that may never be dialled would be a lie.
 *
 * **A redial is the same phone call.** Hanging up and trying again inside
 * [RESEND_WINDOW_MS] sends nothing — the first text still stands, and a flaky link would
 * otherwise turn three redials into three identical messages. In-memory on purpose: the
 * socket's foreground service keeps this process alive across the call, and after a
 * process death a repeat text is merely redundant, not wrong.
 *
 * **The send is its own client on its own thread.** This fires from [Dialer.call] on the
 * main thread, immediately before the app stands aside for the call — there is no
 * coroutine scope to borrow and nothing to wait for. Same construction the socket service
 * uses for [SocketService.newestIsRead]'s client.
 */
object CallAnnounce {

    private const val TAG = "CallAnnounce"

    /** Ten minutes: long enough to cover hang-up-and-redial, short enough that calling
     *  the same person tomorrow announces again. */
    private const val RESEND_WINDOW_MS = 10 * 60 * 1000L

    private val lastSent = HashMap<String, Long>()

    /** Sends the announcement for a just-placed call to [address], if the toggle is on
     *  and [address] is an announceable number. Never throws; failure is a log line —
     *  the call itself is already ringing and must not be disturbed. */
    fun maybeAnnounce(context: Context, address: String) {
        val app = context.applicationContext
        if (!Store.callAnnounce(app)) return
        val handle = handleFor(address) ?: return
        val now = System.currentTimeMillis()
        synchronized(lastSent) {
            val last = lastSent[handle] ?: 0L
            if (now - last < RESEND_WINDOW_MS) return
            lastSent[handle] = now
        }
        val url = Store.baseUrl(app) ?: return
        val password = Store.password(app) ?: return
        val text = messageText(ownNumber(app))
        val method = if (Store.privateApi(app)) "private-api" else "apple-script"
        thread(name = "call-announce") {
            runCatching {
                BlueBubblesApi(url, password).send(
                    chatGuid = "iMessage;-;$handle",
                    text = text,
                    tempGuid = "call-announce-$now",
                    method = method,
                )
            }.onFailure { Log.d(TAG, "announce to $handle failed: $it") }
        }
    }

    /**
     * The iMessage handle for a dialled address, or null when announcing would be wrong.
     *
     * Same normalisation as `ChatViewModel.imessageHandle` for numbers — but stricter
     * about what qualifies at all. An email never got here (you can't ring one, and
     * [Dialer.callable] already refused it), letters mean it isn't a number, and anything
     * under [MIN_DIGITS] digits is a short code — the two-factor robot does not need to
     * be told which phone you are, and `iMessage;-;22000` isn't a chat anyway. A number
     * that fits none of the known shapes is skipped rather than guessed at: a wrong
     * handle is a text to a stranger.
     */
    fun handleFor(address: String): String? {
        val a = address.trim()
        if (a.isEmpty() || a.contains('@') || a.any { it.isLetter() }) return null
        val digits = a.filter { it.isDigit() }
        if (digits.length < MIN_DIGITS) return null
        return when {
            a.startsWith("+") -> "+$digits"
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+$digits"
            else -> null
        }
    }

    /** Seven: the shortest thing that is a diallable subscriber number rather than a
     *  short code. [Dialer.MIN_DIGITS] is deliberately looser (short codes are worth
     *  *calling*); they are not worth *texting about*. */
    private const val MIN_DIGITS = 7

    /** The message itself. One place, so the settings screen's preview and the send can
     *  never disagree. */
    fun messageText(ownNumber: String?): String = if (ownNumber != null) {
        "I am calling you from ${prettyUs(ownNumber)} — it's my dumb phone. " +
            "Please call me back there, not on this number. (This is an automated message.)"
    } else {
        "I am calling you from my dumb phone. Please call me back on the number " +
            "that's calling you, not on this one. (This is an automated message.)"
    }

    /** `+13152122695` → `+1 (315) 212-2695`; anything that isn't a US E.164 number is
     *  shown as it came, because guessing at foreign grouping helps nobody. */
    fun prettyUs(number: String): String {
        val n = number.trim()
        if (!Regex("""^\+1\d{10}$""").matches(n)) return n
        val d = n.removePrefix("+1")
        return "+1 (${d.substring(0, 3)}) ${d.substring(3, 6)}-${d.substring(6)}"
    }

    /**
     * The number this phone calls from: the manual override first ([Store.myNumber] —
     * some SIMs simply don't carry their own number), then the SIM's line-1 number,
     * which needs `READ_PHONE_NUMBERS` — requested when the toggle is switched on, and
     * checked again here because a permission can be revoked between then and the call.
     * Null is an answer, not an error: the message has a wording for it.
     */
    fun ownNumber(context: Context): String? {
        Store.myNumber(context)?.let { return it }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return null
        @Suppress("DEPRECATION")
        return runCatching { tm.line1Number }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
