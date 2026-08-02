package com.gios.lightchat

/**
 * Finding a one-time login code in a message.
 *
 * **Why this is a keyword gate and not a regex for digits.** Almost every short number in a
 * message is not a code — a flight number, a street address, a time, a price, an apartment
 * buzzer, the year. A detector that fires on "meet me at 1830" and pins it to the keyboard's
 * suggestion strip is worse than no detector, because the strip is the place the user has
 * learned to trust without reading. So a message has to *say* it is carrying a code before any
 * number in it is treated as one, and the code is then the number closest to the word that
 * said so.
 *
 * **No Android in here**, deliberately: this is the part that is easy to get subtly wrong and
 * the part a unit test can hold still. The sandbox can compile and run it with `kotlinc` alone,
 * which is the only way anything in this repo gets tested before it is on the phone.
 *
 * Two shapes are recognised:
 *
 * - **The WebOTP line** — `@example.com #123456` — which is a real, specified convention rather
 *   than a heuristic, so it wins outright and skips the keyword gate entirely.
 * - **Everything else**, which is prose with a keyword and a number in it, in either order:
 *   "Your Apple ID code is 123456" and "123456 is your verification code" are both extremely
 *   common and neither can be assumed.
 */
object LoginCodes {

    /**
     * The words that make a number a code.
     *
     * Kept deliberately short. Every addition here widens what counts as a login message, and a
     * false positive costs more than a miss: a missed code means typing six digits, a wrong one
     * means the keyboard offering a stranger's flight number in a password field.
     *
     * "pin" is matched as a whole word only — it is a substring of "shipping", "spinning" and
     * "typing" is one letter away — which the word-boundary scan below handles for every entry.
     */
    private val KEYWORDS = listOf(
        "code",
        "otp",
        "one-time",
        "one time",
        "passcode",
        "pin",
        "verification",
        "verify",
        "2fa",
        "two-factor",
        "authenticate",
        "authentication",
        "security key",
        "log in",
        "login",
        "sign in",
        "signin",
    )

    /**
     * A code short enough to type is at least four characters and at most eight. Below four
     * there is nothing to save the user; above eight it is an account number, an order
     * reference or a tracking id, none of which anybody is waiting to paste into a login box.
     */
    private const val MIN = 4
    private const val MAX = 8

    /** The length nearly every service actually uses — the tiebreak when two candidates are equally close. */
    private const val TYPICAL = 6

    /**
     * The code in [text], or null.
     *
     * @return the code exactly as written, including any letters and their case. A code is
     *   frequently case-sensitive and always opaque; normalising it would be inventing a
     *   different code.
     */
    fun find(text: String?): String? {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return null

        webOtp(body)?.let { return it }

        val keywordAt = keywordPositions(body)
        if (keywordAt.isEmpty()) return null

        val candidates = candidates(body)
        if (candidates.isEmpty()) return null

        // Closest to whatever word claimed a code was coming, then the conventional length,
        // then earliest. Distance first because a message can carry two numbers — "Your code is
        // 481920. Reply STOP to 44398 to opt out." — and the one being talked about is the one
        // beside the talking.
        return candidates.minWithOrNull(
            compareBy(
                { c -> keywordAt.minOf { distance(c, it) } },
                { c -> if (c.text.length == TYPICAL) 0 else 1 },
                { c -> c.start },
            ),
        )?.text
    }

    /** Whether [text] carries a code at all — the alert filter's question, and cheaper to read at the call site. */
    fun looksLikeLogin(text: String?): Boolean = find(text) != null

    /**
     * The WebOTP convention: a final line of `@host #code`, which browsers and iOS both emit and
     * consume. Its whole point is being unambiguous, so it is honoured without a keyword — a
     * message in this shape is a code by construction.
     */
    private fun webOtp(body: String): String? {
        val hash = body.lastIndexOf('#')
        if (hash < 0 || hash == body.lastIndex) return null
        // Only when an `@host` precedes it on the same message, which is what separates the
        // convention from somebody writing "#1" or a hashtag.
        if (!body.substring(0, hash).contains('@')) return null
        val token = body.substring(hash + 1).takeWhile { it.isLetterOrDigit() }
        return token.takeIf { wellFormedCode(it) }
    }

    /** Where each keyword sits, as the index of its first character. A word can appear twice; all of them count. */
    private fun keywordPositions(body: String): List<Int> {
        val lower = body.lowercase()
        val out = ArrayList<Int>()
        for (word in KEYWORDS) {
            var from = 0
            while (true) {
                val at = lower.indexOf(word, from)
                if (at < 0) break
                // Whole words only. Without this "pin" matches "shipping" and every shipping
                // notification in the address book starts offering its tracking number.
                val before = if (at == 0) ' ' else lower[at - 1]
                val afterAt = at + word.length
                val after = if (afterAt >= lower.length) ' ' else lower[afterAt]
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) out.add(at)
                from = at + word.length
            }
        }
        return out
    }

    private data class Candidate(val text: String, val start: Int) {
        val end: Int get() = start + text.length
    }

    /**
     * Every token in [body] that could be a code.
     *
     * Tokens are split on anything that isn't a letter or digit, so a code is never found
     * *inside* a longer run — `1234567890123` yields one 13-character token, which fails the
     * length test and is correctly ignored rather than surrendering its first six digits.
     */
    private fun candidates(body: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        var i = 0
        while (i < body.length) {
            if (!body[i].isLetterOrDigit()) { i++; continue }
            var j = i
            while (j < body.length && body[j].isLetterOrDigit()) j++
            val token = body.substring(i, j)
            if (wellFormedCode(token) && !embedded(body, i, j)) out.add(Candidate(token, i))
            i = j
        }
        return out
    }

    /**
     * A token that is shaped like a code: all digits, or alphanumeric with at least one digit.
     *
     * The digit requirement is what keeps every ordinary word of the right length out — "please",
     * "verify" and "account" are all six letters, and without it the word that triggered the
     * match would frequently be returned as the code it was announcing.
     */
    fun wellFormedCode(token: String): Boolean {
        if (token.length !in MIN..MAX) return false
        if (token.none { it.isDigit() }) return false
        return true
    }

    /**
     * Whether the token is really part of a bigger number that punctuation happens to break up:
     * a price, a decimal, a clock time, a dotted version.
     *
     * **The separator only counts when there is something on the far side of it.** That single
     * condition is the whole subtlety here: `10:30` is one quantity and neither half is a code,
     * but `481920.` is a code at the end of a sentence, and both are "a digit run touching a
     * period or a colon". Looking one character further — is the thing beyond the separator
     * itself alphanumeric? — separates them, and a first attempt that skipped it rejected every
     * code that happened to end a sentence, which is most of them.
     */
    private fun embedded(body: String, start: Int, end: Int): Boolean {
        val before = body.getOrNull(start - 1)
        val after = body.getOrNull(end)
        // A currency mark or a percent needs no far side: it makes the number a quantity on its own.
        if (before == '$' || before == '\u00a3' || before == '\u20ac') return true
        if (after == '%') return true
        if (before != null && before in JOINERS && body.getOrNull(start - 2)?.isLetterOrDigit() == true) return true
        if (after != null && after in JOINERS && body.getOrNull(end + 1)?.isLetterOrDigit() == true) return true
        return false
    }

    /**
     * Separators that join two halves of one quantity. A hyphen is deliberately absent: codes are
     * routinely written `123-456`, and the halves are too short to be candidates on their own.
     */
    private const val JOINERS = ":.,/"

    /** Characters between a token and a keyword, zero when they touch or overlap. */
    private fun distance(c: Candidate, keywordStart: Int): Int = when {
        c.end <= keywordStart -> keywordStart - c.end
        c.start >= keywordStart -> c.start - keywordStart
        else -> 0
    }
}
