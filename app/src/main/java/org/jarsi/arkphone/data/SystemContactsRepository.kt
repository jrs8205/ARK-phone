package org.jarsi.arkphone.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

internal fun dedupeByContactId(rows: List<Contact>): List<Contact> =
    rows.distinctBy { it.id }.sortedBy { it.displayName.lowercase() }

class SystemContactsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ContactsRepository {

    override fun contacts(): Flow<List<Contact>> {
        val resolver = context.contentResolver
        fun query(): List<Contact> {
            if (!permissionChecker.has(Manifest.permission.READ_CONTACTS)) return emptyList()
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
                ContactsContract.CommonDataKinds.Phone.STARRED,
            )
            val rows = mutableListOf<Contact>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    rows += Contact(
                        id = cursor.getLong(0),
                        displayName = name,
                        phoneNumber = cursor.getString(2),
                        photoUri = cursor.getString(3),
                        starred = cursor.getInt(4) == 1,
                    )
                }
            }
            return dedupeByContactId(rows)
        }

        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            if (permissionChecker.has(Manifest.permission.READ_CONTACTS)) {
                resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
            }
            send(Unit)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.conflate().map { query() }.flowOn(ioDispatcher)
    }

    override suspend fun contactDetails(contactId: Long): ContactDetails? = withContext(ioDispatcher) {
        if (!permissionChecker.has(Manifest.permission.READ_CONTACTS)) return@withContext null
        runCatching {
            val header = queryContactHeader(contactId) ?: return@runCatching null
            assembleContactDetails(
                id = contactId,
                displayName = header.displayName,
                photoUri = header.photoUri,
                starred = header.starred,
                rows = queryContactDataRows(contactId),
                typeLabel = ::typeLabel,
            )
        }.getOrNull()
    }

    private data class ContactHeader(
        val displayName: String,
        val photoUri: String?,
        val starred: Boolean,
    )

    private fun queryContactHeader(contactId: Long): ContactHeader? =
        context.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId),
            arrayOf(
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_URI,
                ContactsContract.Contacts.STARRED,
            ),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                ContactHeader(
                    displayName = cursor.getString(0).orEmpty(),
                    photoUri = cursor.getString(1),
                    starred = cursor.getInt(2) == 1,
                )
            } else {
                null
            }
        }

    private fun queryContactDataRows(contactId: Long): List<ContactDataRow> {
        val rows = mutableListOf<ContactDataRow>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
            ),
            "${ContactsContract.Data.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += ContactDataRow(
                    mimeType = cursor.getString(0) ?: continue,
                    data1 = cursor.getString(1),
                    type = if (cursor.isNull(2)) null else cursor.getInt(2),
                    customLabel = cursor.getString(3),
                    data4 = cursor.getString(4),
                )
            }
        }
        return rows
    }

    private fun typeLabel(mimeType: String, type: Int, customLabel: String?): String? =
        when (mimeType) {
            Phone.CONTENT_ITEM_TYPE ->
                Phone.getTypeLabel(context.resources, type, customLabel).toString()
            Email.CONTENT_ITEM_TYPE ->
                Email.getTypeLabel(context.resources, type, customLabel).toString()
            StructuredPostal.CONTENT_ITEM_TYPE ->
                StructuredPostal.getTypeLabel(context.resources, type, customLabel).toString()
            Event.CONTENT_ITEM_TYPE -> when (type) {
                Event.TYPE_BIRTHDAY -> context.getString(R.string.contact_event_birthday)
                Event.TYPE_ANNIVERSARY -> context.getString(R.string.contact_event_anniversary)
                else -> customLabel
            }
            else -> null
        }

    override suspend fun lookupContact(number: String): ContactMatch? = withContext(ioDispatcher) {
        if (number.isBlank()) return@withContext null
        if (!permissionChecker.has(Manifest.permission.READ_CONTACTS)) return@withContext null
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        val projection = arrayOf(
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_URI,
        )
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContactMatch(
                        displayName = cursor.getString(0),
                        photoUri = cursor.getString(1),
                    )
                } else {
                    null
                }
            }
        }.getOrNull()
    }
}
