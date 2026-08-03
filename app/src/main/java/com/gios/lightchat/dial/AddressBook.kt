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
     * The phone's address book as pickable rows: one per number, name and address.
     *
     * **The New Message picker searched the server's contact list alone**, which is the Mac's
     * address book — so somebody saved on the handset could not be found in it and starting a
     * conversation with them meant typing the number out in full. The same one-directional merge
     * that left the conversation list showing digits, in a second place.
     *
     * One row per *number*, not per person, because the picker adds a recipient by address and
     * has to be told which line. A person with a mobile and a work number is two rows, exactly
     * as the server's list already represents them.
     *
     * Rows with no real name are skipped for the same reason as [asNameIndex]: they would be a
     * row whose title and subtitle are the same digits, offering nothing over typing it.
     */
    fun asRecipients(contacts: List<PhoneContact>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (contact in contacts) {
            val name = contact.name.trim()
            if (name.isEmpty() || name.none { it.isLetter() }) continue
            if (contact.numbers.any { it.label == FROM_MESSAGES }) continue
            for (number in contact.numbers) out.add(name to number.raw)
        }
        return out
    }

    /**
     * The phone's address book as a handle → name index.
     *
     * **The other direction of [withKnown], and the one that was missing.** The dialer folded the
     * message index into the address book so the *dialer* could name everybody. Nothing did the
     * reverse, so the conversation list, the thread header and the notifications all named people
     * from the BlueBubbles index alone — which is the Mac's address book. Saving Kate Jacobs on
     * the phone changed nothing anywhere in the app, which is not what "I saved a contact" means.
     *
     * Every number of every contact, not just the first: a person is reached on whichever line
     * they happened to message from, and an index that only knows their mobile names half their
     * conversations. Keys are [key], which is the same normalisation
     * [com.gios.lightchat.Contacts.key] uses, so the two indexes can simply be merged.
     */
    fun asNameIndex(contacts: List<PhoneContact>): Map<String, String> {
        val out = HashMap<String, String>()
        for (contact in contacts) {
            val name = contact.name.trim()
            if (name.isEmpty()) continue
            // **A row titled by its own number is not a name.** [merge] falls back to the number
            // when the address book has no name, which is right for a list you look at and wrong
            // for an index you look *up* — writing it back would put "3152122695" over a real
            // name from the server, which is the exact thing this whole feature exists to stop.
            // Checking for a letter rather than comparing against the number catches it however
            // the fallback was written.
            if (name.none { it.isLetter() }) continue
            // Same again for a row synthesised from the message index, which carries its key as
            // its name and would otherwise round-trip back into the index it came from.
            if (contact.numbers.any { it.label == FROM_MESSAGES }) continue
            for (number in contact.numbers) {
                if (number.key.isNotBlank()) out[number.key] = name
            }
        }
        return out
    }

    /**
     * The phone's address book with everybody BlueBubbles knows folded in.
     *
     * **Two address books, and neither is complete on its own.** The phone's has landlines and
     * people who have never texted; the BlueBubbles index is the Mac's contacts as iMessage sees
     * them, and it holds people who exist on the account but were never saved to this handset —
     * a number you have messaged for a year from the Mac is a stranger to the dialer otherwise.
     * The complaint that motivates this is the honest one: somebody in the list with no name.
     *
     * **The phone wins every collision.** Its rows carry structure — several numbers, a label
     * per number, the user's own choice of default — and the BlueBubbles index is one name per
     * key with none of that. Where both know a key, taking the phone's row loses nothing;
     * taking the other way round would throw away everything but the name.
     *
     * @param known the persisted BlueBubbles index, key → name, as
     *   [com.gios.lightchat.Contacts.asMap] stores it. Its keys are already normalised the same
     *   way [key] normalises: last ten digits for a number, lowercase for an email.
     *
     * Email handles are dropped. This list exists to be dialled from, and a row whose only verb
     * cannot work is a row that lies about what it does — the same reason the address-book query
     * reads numbers and not emails.
     */
    fun withKnown(phone: List<PhoneContact>, known: Map<String, String>): List<PhoneContact> {
        if (known.isEmpty()) return phone
        val seen = HashSet<String>()
        for (contact in phone) for (number in contact.numbers) seen.add(number.key)
        val extra = ArrayList<PhoneContact>()
        for ((k, name) in known) {
            if (k.isBlank() || k in seen) continue
            if (k.any { !it.isDigit() }) continue   // an email, or a key that isn't a number
            if (k.length < MIN_KEY_DIGITS) continue
            seen.add(k)
            extra.add(
                PhoneContact(
                    // Negative, and derived from the key rather than counted: these ids share a
                    // list with real contact ids and are used as LazyColumn keys, so they have to
                    // be unique against those and stable across reloads. A counter would
                    // renumber everybody whenever one contact was added.
                    id = -(k.hashCode().toLong() and 0xFFFFFFFFL) - 1L,
                    name = name.trim().ifBlank { k },
                    numbers = listOf(PhoneNumber(raw = k, key = k, label = FROM_MESSAGES)),
                ),
            )
        }
        if (extra.isEmpty()) return phone
        return (phone + extra).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /**
     * What the subtitle says for somebody who is only in the message index.
     *
     * Named rather than blank, because the difference matters when you are looking at the row:
     * this number has no entry on the phone, so Save on the contact page is the thing to do about
     * it. A silent row would just look like a contact with a missing label.
     */
    const val FROM_MESSAGES = "From messages"

    /** Ten digits is a whole number; a shorter key is a short code the message index picked up. */
    private const val MIN_KEY_DIGITS = 7

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
