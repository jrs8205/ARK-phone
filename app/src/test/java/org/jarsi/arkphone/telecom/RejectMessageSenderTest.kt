package org.jarsi.arkphone.telecom

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.telecom.PhoneAccountHandle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import org.jarsi.arkphone.data.SimAccountRepository
import org.jarsi.arkphone.data.model.SimAccount
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
class RejectMessageSenderTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val permissions = FakePermissionChecker().apply {
        grant(Manifest.permission.SEND_SMS)
    }

    private fun repositoryWith(handle: PhoneAccountHandle?) = object : SimAccountRepository {
        override suspend fun accounts(): List<SimAccount> = emptyList()
        override fun handleFor(accountId: String): PhoneAccountHandle? = handle
    }

    private fun handle(id: String): PhoneAccountHandle =
        PhoneAccountHandle(ComponentName("com.android.phone", "Pstn"), id)

    private fun activeSubscriptions(vararg subs: Pair<Int, String>) {
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(
                subs.map { (id, iccId) ->
                    ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                        .setId(id)
                        .setIccId(iccId)
                        .buildSubscriptionInfo()
                },
            )
    }

    @Test
    @Config(sdk = [29])
    fun `below api 30 the decline sms still rides the sim the call rang on`() {
        // TelephonyManager.getSubscriptionId(handle) is API 30; the platform
        // convention below it makes the handle id the subscription id.
        activeSubscriptions(3 to "89358000000000000001")
        val sender = SmsRejectMessageSender(context, permissions, repositoryWith(handle("3")))

        assertTrue(sender.send("+358441234567", "En voi nyt vastata", "3"))

        @Suppress("DEPRECATION")
        val sub3 = SmsManager.getSmsManagerForSubscriptionId(3)
        assertEquals(
            "En voi nyt vastata",
            shadowOf(sub3).lastSentTextMessageParams?.text,
        )
    }

    @Test
    @Config(sdk = [29])
    fun `an icc shaped account id resolves through the subscription list`() {
        // Some vendors put the ICC id, not the subscription id, into the
        // phone account handle.
        activeSubscriptions(5 to "89358000000000000002")
        val sender = SmsRejectMessageSender(
            context,
            permissions,
            repositoryWith(handle("89358000000000000002")),
        )

        assertTrue(sender.send("+358441234567", "Kokouksessa", "89358000000000000002"))

        @Suppress("DEPRECATION")
        val sub5 = SmsManager.getSmsManagerForSubscriptionId(5)
        assertEquals(
            "Kokouksessa",
            shadowOf(sub5).lastSentTextMessageParams?.text,
        )
    }
}
