package org.jarsi.arkphone.messaging

import android.app.Application
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.FakeTelephonyProvider
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeMessageNotifier
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class IncomingSmsHandlerTest {

    private lateinit var provider: FakeTelephonyProvider
    private lateinit var handler: IncomingSmsHandler
    private val blockedNumbers = FakeBlockedNumbersRepository()
    private val contacts = FakeContactsRepository()
    private val notifier = FakeMessageNotifier()
    private val repository = FakeMessagesRepository()

    @Before
    fun setUp() {
        provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms", provider)
        handler = IncomingSmsHandler(
            context = ApplicationProvider.getApplicationContext<Application>(),
            blockedNumbers = blockedNumbers,
            contactsRepository = contacts,
            messageNotifier = notifier,
            messagesRepository = repository,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `a normal sender is stored unread and notified with the contact name`() = runTest {
        contacts.matchesByNumber["+358441234567"] =
            ContactMatch(displayName = "Matti", photoUri = null)

        val rowUri = handler.handle("+358441234567", "Moro", 1_722_000_000_000L, 1)

        assertNotNull(rowUri)
        val row = provider.smsRows.single()
        assertEquals(0, row.getAsInteger(Telephony.Sms.READ))
        assertEquals(0, row.getAsInteger(Telephony.Sms.SEEN))
        assertEquals(
            Telephony.Sms.MESSAGE_TYPE_INBOX,
            row.getAsInteger(Telephony.Sms.TYPE),
        )
        assertEquals(42L, row.getAsLong(Telephony.Sms.THREAD_ID))
        assertEquals(1_722_000_000_000L, row.getAsLong(Telephony.Sms.DATE_SENT))
        with(notifier.notified.single()) {
            assertEquals(42L, threadId)
            assertEquals("Matti", displayName)
            assertEquals("Moro", body)
            assertEquals(1, subscriptionId)
        }
        assertTrue(repository.refreshCalls > 0)
    }

    @Test
    fun `a blocked sender is stored read and produces no notification`() = runTest {
        blockedNumbers.blocked += "+358441234567"

        val rowUri = handler.handle("+358441234567", "Roskaa", 1_722_000_000_000L, 1)

        assertNotNull(rowUri)
        val row = provider.smsRows.single()
        assertEquals(1, row.getAsInteger(Telephony.Sms.READ))
        assertEquals(1, row.getAsInteger(Telephony.Sms.SEEN))
        assertTrue(notifier.notified.isEmpty())
    }
}
