package org.jarsi.arkphone.messaging

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony

const val ACTION_SMS_SENT = "org.jarsi.arkphone.action.SMS_SENT"
const val ACTION_SMS_DELIVERED = "org.jarsi.arkphone.action.SMS_DELIVERED"
const val ACTION_MMS_SENT = "org.jarsi.arkphone.action.MMS_SENT"

/** The provider update one send-status broadcast asks for; null when the
 *  broadcast is not ours. */
internal fun sentUpdateFor(action: String, resultCode: Int): ContentValues? = when (action) {
    ACTION_SMS_SENT -> ContentValues().apply {
        put(
            Telephony.Sms.TYPE,
            if (resultCode == Activity.RESULT_OK) {
                Telephony.Sms.MESSAGE_TYPE_SENT
            } else {
                Telephony.Sms.MESSAGE_TYPE_FAILED
            },
        )
    }
    ACTION_SMS_DELIVERED -> ContentValues().apply {
        put(Telephony.Sms.STATUS, Telephony.Sms.STATUS_COMPLETE)
    }
    ACTION_MMS_SENT -> ContentValues().apply {
        put(
            Telephony.Mms.MESSAGE_BOX,
            if (resultCode == Activity.RESULT_OK) {
                Telephony.Mms.MESSAGE_BOX_SENT
            } else {
                AndroidMmsSender.MESSAGE_BOX_FAILED
            },
        )
    }
    else -> null
}

/** Receives the PendingIntents [AndroidSmsSender] attaches to a send and
 *  writes the outcome onto the message row they point at. */
class SmsSendStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val rowUri = intent.data ?: return
        val values = sentUpdateFor(intent.action.orEmpty(), resultCode) ?: return
        runCatching { context.contentResolver.update(rowUri, values, null, null) }
    }
}
