package com.danemadsen.curie.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.util.Log
import androidx.core.content.ContextCompat

/** One phone number or email address of a contact. */
internal data class ContactEntry(
    val contactId: Long,
    val name: String,

    /** The phone number or the email address. */
    val value: String,

    /** Higher wins between entries of one contact: the user's default, then primary, then mobile. */
    val preference: Int,

    /** The contacts provider matched this row to the spoken name (for example by nickname). */
    val matchedByProvider: Boolean,
)

internal sealed interface ContactLookup {
    data class Found(val name: String, val value: String) : ContactLookup

    sealed interface Failure : ContactLookup

    /** Several different people match equally well: the model should ask which one. */
    data class Ambiguous(val names: List<String>) : Failure

    data object NotFound : Failure

    data object NoPermission : Failure
}

/** Picks the contact a spoken name means, from the rows the contacts provider returned. */
internal object ContactRanking {
    /** The lowest [NameMatching] score that still counts as the person the user meant. */
    const val MIN_SCORE = 0.5

    /** Rows the provider matched by itself (nicknames, phonetic names) count at least this much. */
    const val PROVIDER_MATCH_SCORE = 0.7

    private const val TIE_TOLERANCE = 0.001
    private const val MAX_CHOICES = 3

    fun resolve(spokenName: String, entries: List<ContactEntry>): ContactLookup {
        val scoredEntries = entries.map { entry -> entry to score(spokenName, entry) }
        val matchingEntries = scoredEntries.filter { (_, score) -> score >= MIN_SCORE }
        if (matchingEntries.isEmpty()) {
            return ContactLookup.NotFound
        }

        val byContact = matchingEntries.groupBy { (entry, _) -> entry.contactId }
        val contacts = byContact.values.map { contactEntries -> bestOfContact(contactEntries) }
        val topScore = contacts.maxOf { (_, score) -> score }
        val tied = contacts.filter { (_, score) -> score >= topScore - TIE_TOLERANCE }

        val tiedNames = tied.map { (entry, _) -> NameMatching.normalize(entry.name) }
        val distinctPeople = tiedNames.distinct()
        if (distinctPeople.size > 1) {
            val displayNames = tied.map { (entry, _) -> entry.name }
            val choices = displayNames.distinct()
            val shownChoices = choices.take(MAX_CHOICES)
            return ContactLookup.Ambiguous(shownChoices)
        }

        // One name: duplicates of the same person (say, synced from two accounts). Use their best entry.
        val tiedEntries = tied.map { (entry, _) -> entry }
        val chosen = tiedEntries.maxBy { entry -> entry.preference }
        return ContactLookup.Found(chosen.name, chosen.value)
    }

    private fun score(spokenName: String, entry: ContactEntry): Double {
        val nameScore = NameMatching.score(spokenName, entry.name)
        if (entry.matchedByProvider) {
            return maxOf(nameScore, PROVIDER_MATCH_SCORE)
        }
        return nameScore
    }

    private fun bestOfContact(contactEntries: List<Pair<ContactEntry, Double>>): Pair<ContactEntry, Double> {
        val contactScore = contactEntries.maxOf { (_, score) -> score }
        val entries = contactEntries.map { (entry, _) -> entry }
        val preferredEntry = entries.maxBy { entry -> entry.preference }
        return preferredEntry to contactScore
    }
}

/**
 * Looks up phone numbers and email addresses by a spoken name. Needs READ_CONTACTS, which the UI
 * requests at first use. The contacts provider is force-queryable, so no <queries> entry is needed.
 */
internal class ContactDirectory(private val context: Context) {

    fun hasPermission(): Boolean {
        val state = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
        return state == PackageManager.PERMISSION_GRANTED
    }

    fun findPhoneNumber(spokenName: String): ContactLookup {
        val source = DataSource(
            filterUri = Phone.CONTENT_FILTER_URI,
            contentUri = Phone.CONTENT_URI,
            valueColumn = Phone.NUMBER,
            typeColumn = Phone.TYPE,
            preferredType = Phone.TYPE_MOBILE,
        )
        return find(spokenName, source)
    }

    fun findEmailAddress(spokenName: String): ContactLookup {
        val source = DataSource(
            filterUri = Email.CONTENT_FILTER_URI,
            contentUri = Email.CONTENT_URI,
            valueColumn = Email.ADDRESS,
            typeColumn = Email.TYPE,
            preferredType = Email.TYPE_HOME,
        )
        return find(spokenName, source)
    }

    /** Where one kind of contact data lives; Phone and Email rows share the other columns. */
    private class DataSource(
        val filterUri: Uri,
        val contentUri: Uri,
        val valueColumn: String,
        val typeColumn: String,
        val preferredType: Int,
    )

    private fun find(spokenName: String, source: DataSource): ContactLookup {
        if (!hasPermission()) {
            return ContactLookup.NoPermission
        }

        return try {
            findWithPermission(spokenName, source)
        } catch (exception: SecurityException) {
            // The permission was revoked between the check and the query.
            Log.w(TAG, "Contacts are no longer readable", exception)
            ContactLookup.NoPermission
        }
    }

    private fun findWithPermission(spokenName: String, source: DataSource): ContactLookup {
        val encodedName = Uri.encode(spokenName)
        val filteredUri = Uri.withAppendedPath(source.filterUri, encodedName)
        val filteredEntries = query(filteredUri, source, matchedByProvider = true)
        val filteredLookup = ContactRanking.resolve(spokenName, filteredEntries)
        if (filteredLookup != ContactLookup.NotFound) {
            return filteredLookup
        }

        // The provider matches word prefixes only: a misheard "Katherine" still finds "Catherine" here.
        val allEntries = query(source.contentUri, source, matchedByProvider = false)
        return ContactRanking.resolve(spokenName, allEntries)
    }

    private fun query(uri: Uri, source: DataSource, matchedByProvider: Boolean): List<ContactEntry> {
        val projection = arrayOf(
            Data.CONTACT_ID,
            Data.DISPLAY_NAME_PRIMARY,
            source.valueColumn,
            source.typeColumn,
            Data.IS_SUPER_PRIMARY,
            Data.IS_PRIMARY,
        )

        val resolver = context.contentResolver
        val cursor = resolver.query(uri, projection, null, null, null) ?: return emptyList()
        return cursor.use { rows -> readEntries(rows, source, matchedByProvider) }
    }

    private fun readEntries(rows: Cursor, source: DataSource, matchedByProvider: Boolean): List<ContactEntry> {
        val contactIdColumn = rows.getColumnIndexOrThrow(Data.CONTACT_ID)
        val nameColumn = rows.getColumnIndexOrThrow(Data.DISPLAY_NAME_PRIMARY)
        val valueColumn = rows.getColumnIndexOrThrow(source.valueColumn)
        val typeColumn = rows.getColumnIndexOrThrow(source.typeColumn)
        val superPrimaryColumn = rows.getColumnIndexOrThrow(Data.IS_SUPER_PRIMARY)
        val primaryColumn = rows.getColumnIndexOrThrow(Data.IS_PRIMARY)

        val entries = mutableListOf<ContactEntry>()
        while (rows.moveToNext()) {
            val name = rows.getString(nameColumn)
            val value = rows.getString(valueColumn)
            if (name.isNullOrBlank() || value.isNullOrBlank()) {
                continue
            }

            val isSuperPrimary = rows.getInt(superPrimaryColumn) != 0
            val isPrimary = rows.getInt(primaryColumn) != 0
            val isPreferredType = rows.getInt(typeColumn) == source.preferredType
            val preference = preference(isSuperPrimary, isPrimary, isPreferredType)

            val contactId = rows.getLong(contactIdColumn)
            val entry = ContactEntry(contactId, name, value, preference, matchedByProvider)
            entries.add(entry)
        }
        return entries
    }

    private fun preference(isSuperPrimary: Boolean, isPrimary: Boolean, isPreferredType: Boolean): Int {
        return when {
            isSuperPrimary -> 3
            isPrimary -> 2
            isPreferredType -> 1
            else -> 0
        }
    }

    private companion object {
        const val TAG = "ContactDirectory"
    }
}
