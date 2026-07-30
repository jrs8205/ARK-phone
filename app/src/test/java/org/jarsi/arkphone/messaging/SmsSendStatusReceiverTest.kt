package org.jarsi.arkphone.messaging

import android.app.Activity
import android.provider.Telephony
import android.telephony.SmsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmsSendStatusReceiverTest {

    @Test
    fun `ok sent result marks the row sent`() {
        val values = sentUpdateFor(ACTION_SMS_SENT, Activity.RESULT_OK)
        assertEquals(
            Telephony.Sms.MESSAGE_TYPE_SENT,
            values!!.getAsInteger(Telephony.Sms.TYPE),
        )
    }

    @Test
    fun `error sent result marks the row failed`() {
        val values = sentUpdateFor(ACTION_SMS_SENT, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        assertEquals(
            Telephony.Sms.MESSAGE_TYPE_FAILED,
            values!!.getAsInteger(Telephony.Sms.TYPE),
        )
    }

    @Test
    fun `delivered result completes the delivery status`() {
        val values = sentUpdateFor(ACTION_SMS_DELIVERED, Activity.RESULT_OK)
        assertEquals(
            Telephony.Sms.STATUS_COMPLETE,
            values!!.getAsInteger(Telephony.Sms.STATUS),
        )
    }

    @Test
    fun `unknown action is ignored`() {
        assertNull(sentUpdateFor("org.example.SOMETHING_ELSE", Activity.RESULT_OK))
    }
}
