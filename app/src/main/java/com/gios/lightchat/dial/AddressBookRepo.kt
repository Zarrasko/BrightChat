package com.gios.lightchat.dial

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's address book, read once into memory.
 *
 * **Read in full and filtered in memory**, rather than re-queried per keystroke —
 * `CONTENT_FILTER_URI` exists and is what a contacts app uses. An address book is a few thousand
 * rows at most, the whole list is wanted anyway for the dialer's resting state, and a query per
 * digit would put a ContentResolver round trip inside the keypad's response time for no benefit.
 * T9 matching also cannot be expressed as a provider selection, so the filter has to happen here
 * whatever the fetch looks like.
 *
 * Numbers only. Emails are in the address book too and this screen cannot ring one, so reading
 * them would be building a list whose second half has no working verb.
 */
class AddressBookRepo(private val context: Context) {

    suspend fun load(): List<PhoneContact> = withContext(Dispatchers.IO) {
        AddressBook.merge(readNumbers()).take(AddressBook.MAX)
    }

    private fun readNumbers(): List<AddressBook.Row> {
        val columns = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
        )
        val out = ArrayList<AddressBook.Row>(256)
        // Wrapped: a revoked permission surfaces as a SecurityException from the resolver rather
        // than as a null cursor, and an empty list that explains itself beats a crash.
        runCatching {
            context.contentResolver
                .query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, columns, null, null, null)
                ?.use { c: Cursor ->
                    val idAt = c.getColumnIndexOrThrow(columns[0])
                    val nameAt = c.getColumnIndexOrThrow(columns[1])
                    val numberAt = c.getColumnIndexOrThrow(columns[2])
                    val typeAt = c.getColumnIndexOrThrow(columns[3])
                    val labelAt = c.getColumnIndexOrThrow(columns[4])
                    val primaryAt = c.getColumnIndexOrThrow(columns[5])
                    while (c.moveToNext()) {
                        val number = c.getString(numberAt) ?: continue
                        if (number.isBlank()) continue
                        val type = c.getInt(typeAt)
                        out += AddressBook.Row(
                            contactId = c.getLong(idAt),
                            name = c.getString(nameAt) ?: "",
                            number = number,
                            // The address book stores a type code and only keeps free text for
                            // CUSTOM, so the human label has to come through the platform's own
                            // string table — which is also how it arrives localised.
                            label = runCatching {
                                ContactsContract.CommonDataKinds.Phone
                                    .getTypeLabel(context.resources, type, c.getString(labelAt))
                                    .toString()
                            }.getOrDefault(""),
                            superPrimary = c.getInt(primaryAt) != 0,
                            mobile = type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                        )
                    }
                }
        }
        return out
    }
}
