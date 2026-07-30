package org.jarsi.arkphone.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject

/** The default SMS app's delivery entry point: the system hands every
 *  incoming text here and expects the app to store it itself. */
@AndroidEntryPoint
class SmsDeliverReceiver : BroadcastReceiver() {

    @Inject lateinit var handler: IncomingSmsHandler

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val address = parts.first().displayOriginatingAddress
            ?: parts.first().originatingAddress
            ?: return
        // All parts of a multipart message arrive in one broadcast, in order.
        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        val timestampMillis = parts.first().timestampMillis
        val subscriptionId = intent.getIntExtra("subscription", -1)
        val pendingResult = goAsync()
        scope.launch {
            try {
                handler.handle(address, body, timestampMillis, subscriptionId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
