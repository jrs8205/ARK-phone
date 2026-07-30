package org.jarsi.arkphone.data

import android.provider.Telephony
import org.jarsi.arkphone.data.model.MessageStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageMappingTest {

    @Test
    fun `outbox and queued rows are sending`() {
        assertEquals(
            MessageStatus.SENDING,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.STATUS_NONE),
        )
        assertEquals(
            MessageStatus.SENDING,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_QUEUED, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `sent row without delivery report is sent`() {
        assertEquals(
            MessageStatus.SENT,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_PENDING),
        )
        assertEquals(
            MessageStatus.SENT,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `sent row with completed delivery report is delivered`() {
        assertEquals(
            MessageStatus.DELIVERED,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_COMPLETE),
        )
    }

    @Test
    fun `failed row is failed`() {
        assertEquals(
            MessageStatus.FAILED,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `incoming row has no status`() {
        assertEquals(
            MessageStatus.NONE,
            smsStatusFrom(Telephony.Sms.MESSAGE_TYPE_INBOX, Telephony.Sms.STATUS_NONE),
        )
    }

    @Test
    fun `mms provider dates are seconds`() {
        assertEquals(1_722_000_000_000L, mmsTimestampMillis(1_722_000_000L))
    }

    @Test
    fun `mms status comes from the message box`() {
        assertEquals(MessageStatus.SENDING, mmsStatusFrom(Telephony.Mms.MESSAGE_BOX_OUTBOX))
        assertEquals(MessageStatus.SENT, mmsStatusFrom(Telephony.Mms.MESSAGE_BOX_SENT))
        assertEquals(MessageStatus.FAILED, mmsStatusFrom(5))
        assertEquals(MessageStatus.NONE, mmsStatusFrom(Telephony.Mms.MESSAGE_BOX_INBOX))
    }
}
