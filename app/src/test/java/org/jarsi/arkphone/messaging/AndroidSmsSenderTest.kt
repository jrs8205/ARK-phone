package org.jarsi.arkphone.messaging

import android.app.Application
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.FakeTelephonyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
class AndroidSmsSenderTest {

    private lateinit var provider: FakeTelephonyProvider
    private lateinit var sender: AndroidSmsSender

    @Before
    fun setUp() {
        provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms", provider)
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)
        sender = AndroidSmsSender(
            context = ApplicationProvider.getApplicationContext<Application>(),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `send inserts an outbox row and hands the parts to the default sms manager`() = runTest {
        val rowUri = sender.send("+358441234567", "Moro")

        assertNotNull(rowUri)
        val row = provider.smsRows.single()
        assertEquals("+358441234567", row.getAsString(Telephony.Sms.ADDRESS))
        assertEquals("Moro", row.getAsString(Telephony.Sms.BODY))
        assertEquals(
            Telephony.Sms.MESSAGE_TYPE_OUTBOX,
            row.getAsInteger(Telephony.Sms.TYPE),
        )
        assertEquals(1, row.getAsInteger(Telephony.Sms.READ))
        assertEquals(1, row.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID))
        assertEquals(42L, row.getAsLong(Telephony.Sms.THREAD_ID))

        val params = shadowOf(defaultSmsManager()).lastSentMultipartTextMessageParams
        assertNotNull(params)
        assertEquals("+358441234567", params.destinationAddress)
        assertEquals(listOf("Moro"), params.parts)
        assertNotNull(params.sentIntents.last())
        assertNotNull(params.deliveryIntents.last())
    }

    @Test
    fun `discard failed removes exactly that row`() = runTest {
        sender.discardFailed(7L)
        assertEquals("content://sms/7", provider.deletedUris.single().toString())
    }

    /** The manager for subscription 1, the shadowed default messaging SIM. */
    private fun defaultSmsManager(): SmsManager =
        ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(SmsManager::class.java)
            .createForSubscriptionId(1)
}
