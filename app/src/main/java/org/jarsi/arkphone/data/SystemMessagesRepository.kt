package org.jarsi.arkphone.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.data.model.Conversation
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemMessagesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MessagesRepository {

    private val refreshSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val conversationsUri: Uri = "content://mms-sms/conversations?simple=true".toUri()

    override fun refresh() {
        refreshSignal.tryEmit(Unit)
    }

    override fun conversations(): Flow<List<Conversation>> {
        val resolver = context.contentResolver
        fun query(): List<Conversation> {
            if (!permissionChecker.has(Manifest.permission.READ_SMS)) return emptyList()
            val conversations = mutableListOf<Conversation>()
            resolver.query(
                conversationsUri,
                arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read"),
                null, null, "date DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val recipientIds = cursor.getString(3).orEmpty()
                        .split(' ').mapNotNull { it.toLongOrNull() }
                    conversations += Conversation(
                        threadId = cursor.getLong(0),
                        addresses = recipientIds.mapNotNull(::canonicalAddress),
                        snippet = cursor.getString(4)?.takeIf { it.isNotBlank() },
                        timestampMillis = cursor.getLong(1),
                        unread = cursor.getInt(5) == 0,
                    )
                }
            }
            return conversations
        }
        var observer: ContentObserver? = null
        return observedQueryFlow(
            hasPermission = { permissionChecker.has(Manifest.permission.READ_SMS) },
            registerObserver = { notifyChange ->
                val registered = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = notifyChange()
                }
                observer = registered
                resolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, registered)
            },
            unregisterObserver = { observer?.let(resolver::unregisterContentObserver) },
            refreshSignal = refreshSignal,
            query = ::query,
        ).flowOn(ioDispatcher)
    }

    private fun canonicalAddress(recipientId: Long): String? =
        context.contentResolver.query(
            "content://mms-sms/canonical-address/$recipientId".toUri(),
            null, null, null, null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    override fun messages(threadId: Long): Flow<List<Message>> {
        val resolver = context.contentResolver
        fun query(): List<Message> {
            if (!permissionChecker.has(Manifest.permission.READ_SMS)) return emptyList()
            val messages = mutableListOf<Message>()
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.THREAD_ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE,
                    Telephony.Sms.STATUS,
                    Telephony.Sms.READ,
                    Telephony.Sms.SUBSCRIPTION_ID,
                ),
                Telephony.Sms.THREAD_ID + " = ?",
                arrayOf(threadId.toString()),
                Telephony.Sms.DATE + " ASC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    messages += Message(
                        id = cursor.getLong(0),
                        threadId = cursor.getLong(1),
                        isMms = false,
                        address = cursor.getString(2).orEmpty(),
                        body = cursor.getString(3),
                        timestampMillis = cursor.getLong(4),
                        incoming = cursor.getInt(5) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        status = smsStatusFrom(cursor.getInt(5), cursor.getInt(6)),
                        subscriptionId = cursor.getInt(8),
                    )
                }
            }
            return messages
        }
        var observer: ContentObserver? = null
        return observedQueryFlow(
            hasPermission = { permissionChecker.has(Manifest.permission.READ_SMS) },
            registerObserver = { notifyChange ->
                val registered = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = notifyChange()
                }
                observer = registered
                resolver.registerContentObserver(Telephony.MmsSms.CONTENT_URI, true, registered)
            },
            unregisterObserver = { observer?.let(resolver::unregisterContentObserver) },
            refreshSignal = refreshSignal,
            query = ::query,
        ).flowOn(ioDispatcher)
    }

    override suspend fun markThreadRead(threadId: Long) {
        withContext(ioDispatcher) {
            runCatching {
                val values = ContentValues().apply {
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.SEEN, 1)
                }
                context.contentResolver.update(
                    Telephony.Sms.CONTENT_URI,
                    values,
                    Telephony.Sms.THREAD_ID + " = ? AND " + Telephony.Sms.READ + " = 0",
                    arrayOf(threadId.toString()),
                )
            }
        }
    }

    override suspend fun deleteThread(threadId: Long): Boolean = withContext(ioDispatcher) {
        runCatching {
            context.contentResolver.delete(
                "content://mms-sms/conversations/$threadId".toUri(),
                null, null,
            ) > 0
        }.getOrDefault(false)
    }

    // Replaced test-first in a later task.
    override suspend fun threadIdsMatchingBody(query: String): Set<Long> = emptySet()
}
