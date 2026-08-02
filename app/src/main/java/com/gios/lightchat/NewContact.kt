package com.gios.lightchat

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

/**
 * Saving an unknown sender to the address book.
 *
 * **This app does not write contacts, and should not.** It hands the number to the contacts app
 * with `ACTION_INSERT_OR_EDIT`, which opens that app's own editor with the field already filled
 * and waits for the user to save. The alternative is `WRITE_CONTACTS`, a dangerous permission
 * that would let this app change an address book it has no other business touching — to save one
 * form it would have to reimplement. Same trade as [Dialer]: hand it over, don't reimplement it.
 *
 * **`INSERT_OR_EDIT`, not `INSERT`.** A number you have been texted by is quite often somebody
 * already in the address book under a different number — a second line, a new phone. `INSERT`
 * makes a duplicate of that person without asking; `INSERT_OR_EDIT` offers the choice between a
 * new contact and adding the number to an existing one, which is the question the user actually
 * has. It costs one constant and is the difference between a clean address book and two Alexes.
 *
 * Only ever offered for a handle that is a number. An iMessage handle is as often an Apple ID,
 * and the contacts editor has nowhere sensible to put one from a phone-number field.
 */
object NewContact {

    /** Whether [address] is worth offering to save — the same test the Call verb uses. */
    fun savable(address: String): Boolean = Dialer.callable(address)

    /**
     * Opens the contacts editor on [address].
     *
     * @param name a name to seed the form with, or blank. The conversation rarely has one worth
     *   seeding — if it did, the sender would not be unknown — but a named group's member is the
     *   case where it does.
     *
     * False if nothing took the intent, which on a phone with no contacts app is the honest
     * answer rather than something to complain about.
     */
    fun create(context: Context, address: String, name: String = ""): Boolean {
        if (!savable(address)) return false
        val intent = Intent(Intent.ACTION_INSERT_OR_EDIT)
            .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
            .putExtra(ContactsContract.Intents.Insert.PHONE, Dialer.dialable(address))
            .putExtra(
                ContactsContract.Intents.Insert.PHONE_TYPE,
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (name.isNotBlank()) intent.putExtra(ContactsContract.Intents.Insert.NAME, name)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }
}
