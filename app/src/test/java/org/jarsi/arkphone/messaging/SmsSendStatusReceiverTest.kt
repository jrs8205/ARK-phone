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
    fun `a failed delivery report does not claim delivered`() {
        val values =
            sentUpdateFor(ACTION_SMS_DELIVERED, Activity.RESULT_OK, deliveryTpStatus = 0x41)
        assertEquals(
            Telephony.Sms.STATUS_FAILED,
            values!!.getAsInteger(Telephony.Sms.STATUS),
        )
    }

    @Test
    fun `a still-trying delivery report stays pending`() {
        val values =
            sentUpdateFor(ACTION_SMS_DELIVERED, Activity.RESULT_OK, deliveryTpStatus = 0x30)
        assertEquals(
            Telephony.Sms.STATUS_PENDING,
            values!!.getAsInteger(Telephony.Sms.STATUS),
        )
    }

    @Test
    fun `a delivered report completes the status`() {
        val values =
            sentUpdateFor(ACTION_SMS_DELIVERED, Activity.RESULT_OK, deliveryTpStatus = 0)
        assertEquals(
            Telephony.Sms.STATUS_COMPLETE,
            values!!.getAsInteger(Telephony.Sms.STATUS),
        )
    }

    @Test
    fun `unknown action is ignored`() {
        assertNull(sentUpdateFor("org.example.SOMETHING_ELSE", Activity.RESULT_OK))
    }

    @Test
    fun `mms sent ok moves the row to the sent box`() {
        val values = sentUpdateFor(ACTION_MMS_SENT, Activity.RESULT_OK)
        assertEquals(
            Telephony.Mms.MESSAGE_BOX_SENT,
            values!!.getAsInteger(Telephony.Mms.MESSAGE_BOX),
        )
    }

    @Test
    fun `mms sent failure moves the row to the failed box`() {
        val values = sentUpdateFor(ACTION_MMS_SENT, SmsManager.RESULT_ERROR_GENERIC_FAILURE)
        assertEquals(5, values!!.getAsInteger(Telephony.Mms.MESSAGE_BOX))
    }
}
