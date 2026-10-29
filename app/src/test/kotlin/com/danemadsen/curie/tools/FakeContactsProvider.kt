package com.danemadsen.curie.tools

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data

/**
 * Stands in for the contacts provider: answers Phone and Email queries, with the filter URIs matching
 * names by word prefix like the real provider does.
 */
class FakeContactsProvider : ContentProvider() {
    data class Row(
        val contactId: Long,
        val name: String,
        val number: String?,
        val email: String?,
        val type: Int = Phone.TYPE_MOBILE,
        val isSuperPrimary: Boolean = false,
    )

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val columns = projection ?: arrayOf(Data.CONTACT_ID, Data.DISPLAY_NAME_PRIMARY)
        val cursor = MatrixCursor(columns)
        queries.add(uri)

        val isEmailQuery = uri.toString().startsWith(Email.CONTENT_URI.toString())
        val filterText = if (uri.pathSegments.contains("filter")) uri.lastPathSegment else null

        for (row in rows) {
            val value = if (isEmailQuery) row.email else row.number
            if (value == null) {
                continue
            }
            if (filterText != null && !matchesFilter(row.name, filterText)) {
                continue
            }

            val values = columns.map { column -> columnValue(column, row, value) }
            cursor.addRow(values)
        }
        return cursor
    }

    private fun matchesFilter(name: String, filterText: String): Boolean {
        val words = name.lowercase().split(' ')
        val filter = filterText.lowercase()
        return words.any { word -> word.startsWith(filter) }
    }

    private fun columnValue(column: String, row: Row, value: String): Any? {
        return when (column) {
            Data.CONTACT_ID -> row.contactId
            Data.DISPLAY_NAME_PRIMARY -> row.name
            Phone.NUMBER, Email.ADDRESS -> value
            Phone.TYPE -> row.type
            Data.IS_SUPER_PRIMARY -> if (row.isSuperPrimary) 1 else 0
            Data.IS_PRIMARY -> 0
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        val rows = mutableListOf<Row>()
        val queries = mutableListOf<Uri>()

        fun reset() {
            rows.clear()
            queries.clear()
        }
    }
}
