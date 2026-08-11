package org.jarsi.arkphone.messaging

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidSmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SmsSender {

    override suspend fun send(address: String, body: String): Uri? =
        withContext(ioDispatcher) {
            runCatching {
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.READ, 1)
                    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                    defaultMessagingSubscriptionId()
                        .takeIf { it >= 0 }
                        ?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
                    put(
                        Telephony.Sms.THREAD_ID,
                        Telephony.Threads.getOrCreateThreadId(context, address),
                    )
                }
                val rowUri = context.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
                    ?: return@runCatching null
                val smsManager = context.defaultSmsManager()
                val parts = smsManager.divideMessage(body)
                // Only the last part reports back, so the whole message makes
                // exactly one sending → sent → delivered transition.
                val sentIntents = ArrayList<PendingIntent?>(parts.size)
                val deliveryIntents = ArrayList<PendingIntent?>(parts.size)
                for (index in parts.indices) {
                    val last = index == parts.lastIndex
                    sentIntents += if (last) statusIntent(ACTION_SMS_SENT, rowUri) else null
                    deliveryIntents +=
                        if (last) statusIntent(ACTION_SMS_DELIVERED, rowUri, mutable = true) else null
                }
                smsManager.sendMultipartTextMessage(
                    address,
                    null,
                    parts,
                    sentIntents,
                    deliveryIntents,
                )
                rowUri
            }.getOrNull()
        }

    override suspend fun discardFailed(messageId: Long) {
        withContext(ioDispatcher) {
            runCatching {
                context.contentResolver.delete(
                    ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, messageId),
                    null,
                    null,
                )
            }
        }
    }

    private fun statusIntent(action: String, rowUri: Uri, mutable: Boolean = false): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            rowUri.hashCode(),
            Intent(action, rowUri, context, SmsSendStatusReceiver::class.java),
            if (mutable) {
                // The delivery intent must stay mutable and reusable: the
                // system skips fillIn on an immutable one — dropping the
                // status-report PDU — and an SMSC may report "still trying"
                // before the final outcome.
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE or
                    PendingIntent.FLAG_ONE_SHOT
            },
        )
}
