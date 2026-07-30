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
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.data.model.MmsAttachment
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject
import javax.inject.Singleton

/** m-notification-ind: an MMS the download has not (yet) replaced. */
private const val MMS_MESSAGE_TYPE_NOTIFICATION = 130
private const val MMS_ADDRESS_TYPE_FROM = 137

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
            messages += mmsMessages(threadId)
            messages.sortBy { it.timestampMillis }
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
                context.contentResolver.update(
                    Telephony.Mms.CONTENT_URI,
                    values,
                    Telephony.Mms.THREAD_ID + " = ? AND " + Telephony.Mms.READ + " = 0",
                    arrayOf(threadId.toString()),
                )
            }
        }
    }

    private fun mmsMessages(threadId: Long): List<Message> {
        val messages = mutableListOf<Message>()
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ,
                Telephony.Mms.SUBSCRIPTION_ID,
                Telephony.Mms.MESSAGE_TYPE,
                Telephony.Mms.CONTENT_LOCATION,
            ),
            Telephony.Mms.THREAD_ID + " = ?",
            arrayOf(threadId.toString()),
            Telephony.Mms.DATE + " ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val messageId = cursor.getLong(0)
                val (body, attachments) = mmsParts(messageId)
                messages += Message(
                    id = messageId,
                    threadId = cursor.getLong(1),
                    isMms = true,
                    address = mmsSender(messageId).orEmpty(),
                    body = body,
                    timestampMillis = mmsTimestampMillis(cursor.getLong(2)),
                    incoming = cursor.getInt(3) == Telephony.Mms.MESSAGE_BOX_INBOX,
                    status = MessageStatus.NONE,
                    subscriptionId = cursor.getInt(5),
                    attachments = attachments,
                    pendingDownload = cursor.getInt(6) == MMS_MESSAGE_TYPE_NOTIFICATION,
                )
            }
        }
        return messages
    }

    private fun mmsParts(messageId: Long): Pair<String?, List<MmsAttachment>> {
        var body: String? = null
        val attachments = mutableListOf<MmsAttachment>()
        context.contentResolver.query(
            "content://mms/$messageId/part".toUri(),
            arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contentType = cursor.getString(1).orEmpty()
                when {
                    contentType == "text/plain" ->
                        body = body ?: cursor.getString(2)?.takeIf { it.isNotBlank() }
                    contentType.startsWith("image/") ->
                        attachments += MmsAttachment(
                            partUri = "content://mms/part/" + cursor.getLong(0),
                            mimeType = contentType,
                        )
                }
            }
        }
        return body to attachments
    }

    private fun mmsSender(messageId: Long): String? =
        context.contentResolver.query(
            "content://mms/$messageId/addr".toUri(),
            arrayOf("address", "type"),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) == MMS_ADDRESS_TYPE_FROM) return cursor.getString(0)
            }
            null
        }

    override suspend fun deleteThread(threadId: Long): Boolean = withContext(ioDispatcher) {
        runCatching {
            context.contentResolver.delete(
                "content://mms-sms/conversations/$threadId".toUri(),
                null, null,
            ) > 0
        }.getOrDefault(false)
    }

    override suspend fun threadIdsMatchingBody(query: String): Set<Long> = withContext(ioDispatcher) {
        if (query.isBlank() || !permissionChecker.has(Manifest.permission.READ_SMS)) {
            return@withContext emptySet()
        }
        val threadIds = mutableSetOf<Long>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                Telephony.Sms.BODY + " LIKE ? ESCAPE '\\'",
                arrayOf("%" + escapeLikeQuery(query) + "%"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    threadIds += cursor.getLong(0)
                }
            }
        }
        threadIds
    }
}

/** Makes raw user input literal inside a LIKE pattern with ESCAPE '\'. */
internal fun escapeLikeQuery(raw: String): String =
    buildString(raw.length) {
        raw.forEach { c ->
            when (c) {
                '\\', '%', '_' -> append('\\').append(c)
                else -> append(c)
            }
        }
    }
