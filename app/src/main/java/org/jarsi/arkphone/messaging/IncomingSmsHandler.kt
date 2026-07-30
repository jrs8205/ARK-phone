package org.jarsi.arkphone.messaging

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.MessagesRepository
import org.jarsi.arkphone.di.IoDispatcher
import javax.inject.Inject

/** Everything that happens to one delivered SMS, testable without a receiver. */
class IncomingSmsHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blockedNumbers: BlockedNumbersRepository,
    private val contactsRepository: ContactsRepository,
    private val messageNotifier: MessageNotifier,
    private val messagesRepository: MessagesRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Returns the inserted row URI (null on failure). Blocked senders are
     *  stored already read and produce no notification. */
    suspend fun handle(
        address: String,
        body: String,
        timestampMillis: Long,
        subscriptionId: Int,
    ): Uri? = withContext(ioDispatcher) {
        runCatching {
            val blocked = blockedNumbers.isBlocked(address)
            val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.DATE_SENT, timestampMillis)
                put(Telephony.Sms.READ, if (blocked) 1 else 0)
                put(Telephony.Sms.SEEN, if (blocked) 1 else 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
                put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                put(Telephony.Sms.THREAD_ID, threadId)
            }
            val rowUri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            messagesRepository.refresh()
            if (rowUri != null && !blocked) {
                val displayName = contactsRepository.lookupContact(address)?.displayName
                messageNotifier.notifyMessage(threadId, address, displayName, body, timestampMillis)
            }
            rowUri
        }.getOrNull()
    }
}
