package com.gios.lightchat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat

/**
 * Calling somebody from a conversation.
 *
 * **The number is placed, not typed.** `ACTION_CALL` dials straight from the tap, which needs
 * `CALL_PHONE` — the first dangerous permission this app has ever asked for, and worth being
 * deliberate about. It is requested at the moment of the first call rather than at launch, and
 * a refusal is not a dead end: the call falls back to `ACTION_DIAL`, which opens the dialer and
 * waits for the green button. Nothing here ever calls without a tap that said "call".
 *
 * **v1.3 shipped this as DIAL only, and the number never arrived.** The cause was
 * [Uri.fromParts], which is documented as taking a *decoded* scheme-specific part and therefore
 * percent-encodes it on the way out — so an E.164 handle became `tel:%2B13152122695`. A dialer
 * that decodes it is fine; the Light Phone's does not, and it opened on an empty keypad with no
 * error anywhere, which looks exactly like the intent being ignored. The number now goes through
 * [dialable] and into a URI built by [Uri.parse], where a `+` stays a `+`.
 */
object Dialer {

    /**
     * Whether [address] is something a dialer could do anything with.
     *
     * **An iMessage handle is a phone number or an email address**, and the two are not
     * distinguishable by asking Android — they arrive from the BlueBubbles server as opaque
     * strings, and half of a real address book's iMessage handles are Apple IDs. An email
     * address hands the dialer a string of letters, which either opens an empty keypad or is
     * refused outright; either way the row lied about what it does.
     *
     * The test is deliberately generous about *format* and strict about *kind*: anything with
     * an `@` in it is an address and never callable, and anything else needs enough digits to
     * be a number somebody could ring. Short codes pass, which is intentional — the number
     * that texted you a verification code is one you may well want to ring back.
     */
    fun callable(address: String): Boolean {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains('@')) return false
        // Letters anywhere mean this is not a number that was written oddly, it is something
        // else — a service name, a garbled handle. `+1 (315) 212-2695` has none.
        if (trimmed.any { it.isLetter() }) return false
        return trimmed.count { it.isDigit() } >= MIN_DIGITS
    }

    /**
     * Three, so a short code reaches the keypad. Below that there is nothing to dial: a
     * one- or two-digit handle is a parse failure on the server's side, not a number.
     */
    private const val MIN_DIGITS = 3

    /**
     * The number reduced to the characters a dialer acts on.
     *
     * Spaces, brackets, dots and dashes are how a human writes a number down and mean nothing
     * to the network; they also have to be percent-encoded to survive a URI, which is where
     * this whole feature came apart the first time. Stripping them removes the need to encode
     * anything at all in the common case.
     *
     * What survives is the set that changes what gets dialled: digits, a leading `+` for
     * E.164, `*` and `#` for feature codes, and `,` and `;` — the pause and wait characters
     * that make a stored extension work. Deliberately not [android.telephony.PhoneNumberUtils]
     * `stripSeparators`, which does the same job: keeping it here keeps it pure Kotlin, so the
     * one part of this that can be quietly wrong is the part a unit test can hold still.
     */
    fun dialable(address: String): String =
        address.trim().filter { it.isDigit() || it in DIALABLE }

    private const val DIALABLE = "+*#,;"

    /**
     * Whether this phone can ring anybody at all — the question the Call verb is gated on.
     *
     * **Telephony first, a resolvable dialer second, and the order matters.** The obvious check
     * is "does an activity handle `ACTION_DIAL`", which is what NotebookLink does for its note
     * link and what v1.3 copied. It is the wrong question here for two reasons. `ACTION_CALL`
     * does not go to an activity at all — it goes to the telecom stack, so a phone that places
     * calls perfectly well can resolve no DIAL activity and the verb would hide itself on a
     * working phone. And LightOS's own dialer is the only one installed, so a single app being
     * strict about its intent filters decides whether the feature appears.
     *
     * `FEATURE_TELEPHONY` is the honest test: it says the hardware can make a call. The DIAL
     * lookup is kept as a second chance rather than a requirement, so a build with an unusual
     * telephony declaration and a normal dialer still gets the verb.
     */
    fun available(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
            // MATCH_DEFAULT_ONLY, not 0: an implicit startActivity only ever launches a filter
            // carrying CATEGORY_DEFAULT, so a plain query predicts more than the launch delivers.
            pm.queryIntentActivities(probe(), PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
    }.getOrDefault(false)

    /** Whether `CALL_PHONE` has been granted, i.e. whether [call] will do anything. */
    fun canCallDirectly(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Places the call. False if the number isn't callable, the permission isn't held, or
     * nothing took the intent — every one of which the caller answers by falling back to
     * [dial] rather than by reporting a failure.
     *
     * The permission is re-checked here and not assumed from the caller's own check: it can be
     * revoked from settings between one composition and the tap, and `ACTION_CALL` without it
     * is a `SecurityException` that kills the process rather than an error anybody sees.
     */
    fun call(context: Context, address: String): Boolean {
        if (!callable(address) || !canCallDirectly(context)) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_CALL, telUri(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Ring [address] by whatever route works, best first.
     *
     * `ACTION_CALL` when the permission is held, because it hands the number to the telecom
     * stack and never asks the dialer app to parse anything — which on this phone is the
     * difference between a call and an empty keypad. `ACTION_DIAL` otherwise, which is the only
     * thing available without the permission and still better than nothing.
     *
     * Returns false only when neither worked, which on a phone with telephony means something
     * is wrong rather than something is missing.
     */
    fun ring(context: Context, address: String): Boolean =
        call(context, address) || dial(context, address)

    /** Opens the dialer on [address]. False if it isn't callable, or nothing took the intent. */
    fun dial(context: Context, address: String): Boolean {
        if (!callable(address)) return false
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DIAL, telUri(address)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)
    }

    /**
     * The `tel:` URI.
     *
     * `Uri.parse` over an already-reduced number, which is the opposite of what v1.3 did and
     * the reason it didn't work. [dialable] has already removed everything that would need
     * encoding except `#`, and that one is escaped by hand — unescaped it is read as the start
     * of a fragment, so a stored feature code would arrive as half a number.
     */
    private fun telUri(address: String): Uri =
        Uri.parse("tel:" + dialable(address).replace("#", "%23"))

    private fun probe(): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:0"))
}
