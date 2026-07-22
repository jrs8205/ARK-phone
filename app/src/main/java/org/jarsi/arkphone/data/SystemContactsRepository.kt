package org.jarsi.arkphone.data

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.jarsi.arkphone.data.model.Contact
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

    override fun contacts(): Flow<List<Contact>> = callbackFlow {
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

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(query())
            }
        }
        if (permissionChecker.has(Manifest.permission.READ_CONTACTS)) {
            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        }
        trySend(query())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(ioDispatcher)
}
