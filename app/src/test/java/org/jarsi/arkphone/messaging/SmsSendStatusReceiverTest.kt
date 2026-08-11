package org.jarsi.arkphone.messaging

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `an mms sent broadcast deletes the staged pdu`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(File(context.cacheDir, "mms").apply { mkdirs() }, "mms-send-42.pdu")
        file.writeBytes(byteArrayOf(1))

        SmsSendStatusReceiver().onReceive(
            context,
            Intent(ACTION_MMS_SENT, "content://mms/42".toUri()),
        )

        assertFalse(file.exists())
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
