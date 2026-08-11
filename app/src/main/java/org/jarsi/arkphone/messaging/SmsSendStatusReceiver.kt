package org.jarsi.arkphone.messaging

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

const val ACTION_SMS_SENT = "org.jarsi.arkphone.action.SMS_SENT"
const val ACTION_SMS_DELIVERED = "org.jarsi.arkphone.action.SMS_DELIVERED"
const val ACTION_MMS_SENT = "org.jarsi.arkphone.action.MMS_SENT"

/** The provider update one send-status broadcast asks for; null when the
 *  broadcast is not ours. */
internal fun sentUpdateFor(
    action: String,
    resultCode: Int,
    deliveryTpStatus: Int? = null,
): ContentValues? = when (action) {
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
        put(Telephony.Sms.STATUS, deliveryStatusColumn(deliveryTpStatus))
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

/** Maps a status report's 3GPP TP-Status octet to the provider column. The
 *  provider constants are the 3GPP range starts: below 0x20 delivered, below
 *  0x40 still trying, the rest permanently failed. The delivery broadcast
 *  itself always carries RESULT_OK, so the octet is the only real outcome; a
 *  report without one keeps the old assume-delivered behavior. */
internal fun deliveryStatusColumn(tpStatus: Int?): Int = when {
    tpStatus == null -> Telephony.Sms.STATUS_COMPLETE
    tpStatus < Telephony.Sms.STATUS_PENDING -> Telephony.Sms.STATUS_COMPLETE
    tpStatus < Telephony.Sms.STATUS_FAILED -> Telephony.Sms.STATUS_PENDING
    else -> Telephony.Sms.STATUS_FAILED
}

/** Receives the PendingIntents [AndroidSmsSender] attaches to a send and
 *  writes the outcome onto the message row they point at. */
class SmsSendStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val rowUri = intent.data ?: return
        val values =
            sentUpdateFor(intent.action.orEmpty(), resultCode, deliveryTpStatusFrom(intent))
                ?: return
        runCatching { context.contentResolver.update(rowUri, values, null, null) }
    }

    private fun deliveryTpStatusFrom(intent: Intent): Int? = runCatching {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val format = intent.getStringExtra("format")
        val message = if (format != null) {
            SmsMessage.createFromPdu(pdu, format)
        } else {
            @Suppress("DEPRECATION")
            SmsMessage.createFromPdu(pdu)
        }
        message?.status
    }.getOrNull()
}
