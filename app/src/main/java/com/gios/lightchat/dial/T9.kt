package com.gios.lightchat.dial

/**
 * Typing a name on a number pad.
 *
 * **One field, two meanings, no mode switch.** A keypad on a phone with an address book is
 * asking two questions at once — "ring this number" and "find this person" — and every dialer
 * worth using answers both from the same digits. `426` is a number you could dial and it is also
 * "gam", "ian" and "haм"; the pad does not make you say which.
 *
 * The mapping is the one printed on every phone since the 1960s and unchanged since: 2=ABC,
 * 3=DEF, 4=GHI, 5=JKL, 6=MNO, 7=PQRS, 8=TUV, 9=WXYZ. 1 and 0 carry no letters, which is why they
 * never match a name and only ever mean themselves.
 *
 * No Android in here, deliberately. Matching is the part of a dialer that is quietly wrong —
 * off by a word boundary, or matching the middle of every name for a two-digit query — and it is
 * the part a unit test can hold still.
 */
object T9 {

    /**
     * Letters to digits, indexed by `c - 'a'`.
     *
     * A string rather than a `Map<Char, Char>`: this is called once per character of every name
     * in the address book on every keystroke, and an array index is the difference between that
     * being free and being a hash lookup a few hundred thousand times.
     */
    private const val KEYS = "22233344455566677778889999"

    /** The digit [c] sits on, or null if it isn't a letter that appears on a keypad. */
    fun digitFor(c: Char): Char? {
        val lower = c.lowercaseChar()
        if (lower < 'a' || lower > 'z') return null
        return KEYS[lower - 'a']
    }

    /**
     * Whether [name] matches [digits] typed on the pad.
     *
     * **At the start of a word, never in the middle**, which is the same rule the message
     * picker's letter search follows and for the same reason: an infix match on a full address
     * book returns half of it for two digits, and the whole value of typing is that the list
     * gets shorter. "Lupo" answers to `58`; it does not answer to `87` because "up" starts no
     * word in it.
     *
     * A run has to be contiguous letters, so a space or a hyphen ends it — `4265` will not span
     * "Gia Nni". That costs nothing, because the second word is reachable as a word start of its
     * own, and it avoids the surprise of a query matching across a gap the user can see.
     */
    fun matchesName(name: String, digits: String): Boolean {
        if (digits.isEmpty()) return true
        if (digits.any { digitFor(it) != null }) return false
        for (i in name.indices) {
            if (!name[i].isLetter()) continue
            // A word start: the first character, or the first letter after something that isn't.
            if (i != 0 && name[i - 1].isLetter()) continue
            if (runMatches(name, i, digits)) return true
        }
        return false
    }

    private fun runMatches(name: String, start: Int, digits: String): Boolean {
        var at = start
        for (d in digits) {
            if (at >= name.length) return false
            if (digitFor(name[at]) != d) return false
            at++
        }
        return true
    }

    /**
     * Whether [number] contains [digits], comparing digits only.
     *
     * A substring rather than a prefix, unlike names. A number is searched by the part of it
     * somebody remembers, which is the tail far more often than the country code — the same
     * reasoning behind matching the last ten digits when de-duplicating handles.
     */
    fun matchesNumber(number: String, digits: String): Boolean {
        if (digits.isEmpty()) return true
        val haystack = number.filter { it.isDigit() }
        val needle = digits.filter { it.isDigit() }
        if (needle.isEmpty()) return false
        return haystack.contains(needle)
    }

    /**
     * How many digits before a query is worth dialling as a number in its own right.
     *
     * Below this the pad is being used to search and offering "Call 42" is noise; at or above it
     * the user may well be typing a number that is in nobody's address book, which is the one
     * thing a contacts list cannot help with.
     */
    const val DIAL_ROW_AFTER = 3
}
