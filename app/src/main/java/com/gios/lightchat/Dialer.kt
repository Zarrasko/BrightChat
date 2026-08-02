package com.gios.lightchat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Calling somebody from a conversation.
 *
 * **This app does not place calls, and should not.** It hands the number to whatever holds
 * the dialer and stops there — `ACTION_DIAL`, which opens the calling app with the number
 * filled in and waits for the green button. The alternative, `ACTION_CALL`, rings straight
 * from the tap and needs `CALL_PHONE`, a permission this app has never held and has no other
 * use for. It would also make a misplaced thumb on the contact page into a call to somebody,
 * with no undo — on a page whose other verbs are Remove and Leave, and which is scrolled with
 * the wheel precisely so a thumb isn't over the taps.
 *
 * Same shape as [NotebookLink]: ask whether anything will take the intent, then hand it over,
 * and treat "nothing did" as an ordinary answer rather than an error. A Light Phone always has
 * a dialer, but a build of LightOS without one is not this app's problem to crash over.
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
     * that texted you a verification code is one you may well want to look at, and
     * `ACTION_DIAL` only ever fills the keypad in.
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

    /** Whether anything on this phone would take a dial intent. */
    fun available(context: Context): Boolean = runCatching {
        context.packageManager
            // MATCH_DEFAULT_ONLY, not 0, for the reason spelled out in NotebookLink.available:
            // an implicit startActivity only ever launches a filter carrying CATEGORY_DEFAULT,
            // so a plain query predicts more than the launch delivers — and the row would
            // offer a call that then refuses to happen.
            .queryIntentActivities(probe(), PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()
    }.getOrDefault(false)

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
     * **`Uri.fromParts`, never `Uri.parse("tel:$number")`.** A `#` in a number — which is how
     * a stored extension or a carrier feature code is written — terminates a parsed URI at the
     * fragment, so everything from the hash onward is silently dropped and the dialer opens on
     * half a number. `fromParts` treats the number as an opaque part and encodes it, which is
     * also what makes the `+` of an E.164 handle survive.
     */
    private fun telUri(address: String): Uri = Uri.fromParts("tel", address.trim(), null)

    private fun probe(): Intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "0", null))
}
