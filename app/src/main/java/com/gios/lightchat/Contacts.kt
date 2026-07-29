package com.gios.lightchat

/**
 * Address → name lookup built from the BlueBubbles server's address book (the
 * Mac's contacts — the same source iMessage uses). The server stores numbers in
 * many formats (`+1 (315) 212-2695`, `608-264-6591`, `+16082138014`), so keys are
 * normalized: phone numbers to their last 10 digits, emails to lowercase. That
 * collapses every variant onto one key, matching the E.164 handles the message
 * API returns.
 */
class Contacts(private val byKey: Map<String, String> = emptyMap()) {

    /** Full name for an address, or null if not in the address book. */
    fun name(address: String): String? = byKey[key(address)]

    /** A conversation's human title — iMessage-style: full name for 1:1, first
     *  names for groups, explicit group name if one is set. */
    fun title(conversation: Conversation): String = when {
        conversation.displayName.isNotBlank() -> conversation.displayName
        conversation.participants.size == 1 -> label(conversation.participants[0], firstNameOnly = false)
        conversation.participants.isEmpty() -> "Unknown"
        else -> conversation.participants.joinToString(", ") { label(it, firstNameOnly = true) }
    }

    /**
     * Whether this conversation has a name behind it — the Known/Unknown split in
     * the conversation list. True if the group carries an explicit name, or if any
     * participant resolves in the address book. A group is Known as soon as one
     * member is: "Liz, +1 315 212 2695" belongs with the people you know, not with
     * the spam. An empty participant list is Unknown rather than crashing.
     */
    fun knows(conversation: Conversation): Boolean =
        conversation.displayName.isNotBlank() ||
            conversation.participants.any { name(it) != null }

    /** Sender label inside a thread (first name keeps group rows short). */
    fun sender(address: String): String = label(address, firstNameOnly = true)

    /** The normalized key → name map, for persistence (see [Store.setContacts]). */
    fun asMap(): Map<String, String> = byKey

    private fun label(address: String, firstNameOnly: Boolean): String {
        val name = name(address) ?: return address
        return if (firstNameOnly) name.substringBefore(" ") else name
    }

    companion object {
        /** Normalizes an address to its lookup key. */
        fun key(address: String): String {
            val a = address.trim()
            if (a.contains("@")) return a.lowercase()
            val digits = a.filter { it.isDigit() }
            return if (digits.length >= 10) digits.takeLast(10) else digits
        }

        /** Rebuilds the index from an already-normalized key → name map (the form
         *  [asMap] persists), skipping re-normalization. */
        fun fromMap(byKey: Map<String, String>): Contacts = Contacts(byKey)

        /** Builds the index from (address, name) pairs; first name wins per key. */
        fun from(pairs: List<Pair<String, String>>): Contacts {
            val map = HashMap<String, String>()
            for ((address, name) in pairs) {
                val k = key(address)
                if (k.isNotEmpty() && name.isNotBlank()) map.putIfAbsent(k, name)
            }
            return Contacts(map)
        }
    }
}
