package com.gios.lightchat.share

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.gios.lightchat.api.Store
import com.gios.lightchat.db.MessageStore
import java.time.LocalDate
import java.time.ZoneId

/**
 * Who you talked to on a day, offered to the rest of the collection.
 *
 * For LightNotebook's journal, where who you spoke to is as much a part of a day as where you were.
 * Names only — **no message text ever leaves this app**, and nothing here says what was said. That
 * line is deliberate and worth keeping: a journal wants "talked to Alex", and anything more is this
 * app handing over your conversations.
 *
 * Read-only, by date, and answered from the local message table — so it is retroactive across
 * everything this phone has synced rather than only what has happened since some recorder started.
 *
 * `content://com.gios.lightchat.talked/talked/2026-07-30`
 */
class TalkedProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(COLUMNS)
        val context = context ?: return cursor
        val day = uri.lastPathSegment.orEmpty()
        // Checked before use: exported, and it is parsed into a date either way.
        val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return cursor

        val zone = ZoneId.systemDefault()
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val store = MessageStore.get(context)
        val contacts = Store.contacts(context)
        // The chat list, so a guid can be given a human name. Read once rather than per row.
        val byGuid = store.chats().associateBy { it.guid }

        store.talkedTo(from, to).forEach { talked ->
            val conversation = byGuid[talked.chatGuid] ?: return@forEach
            cursor.addRow(
                arrayOf<Any?>(
                    talked.firstMs,
                    talked.lastMs,
                    // The same iMessage-style title the app itself shows: a full name for a
                    // one-to-one, first names for a group, the explicit name when one is set.
                    contacts.title(conversation),
                    if (conversation.isGroup) 1 else 0,
                    talked.messages,
                    if (talked.theyReplied) 1 else 0,
                ),
            )
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.lightchat.talked"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        val COLUMNS = arrayOf("first_ms", "last_ms", "name", "is_group", "messages", "they_replied")
    }
}
