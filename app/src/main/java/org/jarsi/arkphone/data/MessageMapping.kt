package org.jarsi.arkphone.data

import android.provider.Telephony
import org.jarsi.arkphone.data.model.MessageStatus

/** Maps an sms table row's TYPE and STATUS columns to one UI status. */
internal fun smsStatusFrom(type: Int, status: Int): MessageStatus = when (type) {
    Telephony.Sms.MESSAGE_TYPE_OUTBOX,
    Telephony.Sms.MESSAGE_TYPE_QUEUED,
    -> MessageStatus.SENDING
    Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageStatus.FAILED
    Telephony.Sms.MESSAGE_TYPE_SENT ->
        if (status == Telephony.Sms.STATUS_COMPLETE) MessageStatus.DELIVERED else MessageStatus.SENT
    else -> MessageStatus.NONE
}

/** The mms table stores DATE in seconds where sms stores milliseconds. */
internal fun mmsTimestampMillis(providerDateSeconds: Long): Long = providerDateSeconds * 1000

/** MMS send progress lives in MESSAGE_BOX; there are no delivery reports.
 *  Box 5 is the app's own failed marker (see AndroidMmsSender). */
internal fun mmsStatusFrom(messageBox: Int): MessageStatus = when (messageBox) {
    Telephony.Mms.MESSAGE_BOX_OUTBOX -> MessageStatus.SENDING
    Telephony.Mms.MESSAGE_BOX_SENT -> MessageStatus.SENT
    5 -> MessageStatus.FAILED
    else -> MessageStatus.NONE
}
