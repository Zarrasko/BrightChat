package com.gios.lightchat.dial

/**
 * One way to ring somebody, as the address book wrote it.
 *
 * [key] is the last ten digits, which is how the same phone written five ways — `+1 (212)
 * 555-0148`, `212-555-0148`, `2125550148` — collapses to one row. Comparing whole numbers fails
 * on exactly the pairs a person considers identical, because only one of them bothered with the
 * country code.
 */
data class PhoneNumber(
    val raw: String,
    val key: String,
    /** "Mobile", "Home", "Work"… as the address book labels it. Blank when it doesn't. */
    val label: String = "",
)

/** A person in the phone's address book, and every number for them, best first. */
data class PhoneContact(
    val id: Long,
    val name: String,
    val numbers: List<PhoneNumber>,
) {
    val primary: PhoneNumber? get() = numbers.firstOrNull()

    /** The row's second line: the number, with its label when there is one. */
    val subtitle: String
        get() = primary?.let { if (it.label.isBlank()) it.raw else "${it.label} · ${it.raw}" }.orEmpty()
}

/**
 * The address book, with no Android in it.
 *
 * Split from the ContentResolver query for the same reason the message picker's [Recipients] is:
 * normalising a number and deciding whether a query matches are the two things here that are
 * easy to get subtly wrong, and the cursor loop around them is boring by comparison.
 */
object AddressBook {

    /** Above this the list is a search box rather than a list — but the list still has to end. */
    const val MAX = 2000

    private const val KEY_DIGITS = 10

    fun key(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length <= KEY_DIGITS) digits else digits.takeLast(KEY_DIGITS)
    }

    /** One address-book row, as read from the cursor. */
    data class Row(
        val contactId: Long,
        val name: String,
        val number: String,
        val label: String = "",
        /** The address book's `IS_SUPER_PRIMARY` — the user's own choice of default. */
        val superPrimary: Boolean = false,
        /** A number typed as a mobile. Worth preferring: it's the one that gets answered. */
        val mobile: Boolean = false,
    )

    /**
     * Collapses rows into people.
     *
     * Rows arrive one per number, so somebody with a mobile, a landline and a work line is three
     * rows sharing a contact id. Within a person the numbers are de-duplicated by [key] and
     * ordered by the user's own default, then mobiles, then the rest — rather than by whatever
     * order the provider happened to return, which is insertion order, so a landline typed in
     * first would become the number the Call verb rings with nothing in the UI to say so.
     *
     * A row with no name is kept and titled by its own number: an unsaved number is still
     * somebody you might ring, and dropping it would make the list quietly incomplete.
     */
    fun merge(rows: List<Row>): List<PhoneContact> {
        val byContact = LinkedHashMap<Long, MutableList<Row>>()
        for (row in rows) {
            if (row.number.isBlank()) continue
            byContact.getOrPut(row.contactId) { mutableListOf() }.add(row)
        }
        val out = ArrayList<PhoneContact>(byContact.size)
        for ((id, group) in byContact) {
            val seen = HashSet<String>()
            val numbers = group
                .sortedWith(
                    compareBy(
                        { if (it.superPrimary) 0 else 1 },
                        { if (it.mobile) 0 else 1 },
                    ),
                )
                .mapNotNull { row ->
                    val k = key(row.number)
                    if (k.isBlank() || !seen.add(k)) {
                        null
                    } else {
                        PhoneNumber(raw = row.number.trim(), key = k, label = row.label.trim())
                    }
                }
            if (numbers.isEmpty()) continue
            val name = group.firstNotNullOfOrNull { it.name.trim().ifBlank { null } }
                ?: numbers.first().raw
            out.add(PhoneContact(id = id, name = name, numbers = numbers))
        }
        // Case-insensitive, so `alex` doesn't sort below every capitalised name.
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * The address book filtered by what has been typed on the pad.
     *
     * **Name matches come first, then number matches**, and the split is worth the extra pass.
     * Typing `426` because you mean "Gia" and getting four people whose numbers happen to
     * contain 426 above her is the search failing at the only thing it was asked to do. Within
     * each group the address book's own alphabetical order is kept, because a second ordering
     * rule the user cannot see is worse than none.
     *
     * An empty query returns everything, so the dialer at rest is a contacts list — which is the
     * point of the screen being one thing rather than two.
     */
    fun search(all: List<PhoneContact>, digits: String): List<PhoneContact> {
        val query = digits.filter { it.isDigit() }
        if (query.isEmpty()) return all
        val byName = ArrayList<PhoneContact>()
        val byNumber = ArrayList<PhoneContact>()
        for (contact in all) {
            when {
                T9.matchesName(contact.name, query) -> byName.add(contact)
                contact.numbers.any { T9.matchesNumber(it.raw, query) } -> byNumber.add(contact)
            }
        }
        return byName + byNumber
    }
}
