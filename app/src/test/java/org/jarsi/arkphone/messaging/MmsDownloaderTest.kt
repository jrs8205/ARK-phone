package org.jarsi.arkphone.messaging

import android.app.Application
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.FakeTelephonyProvider
import org.jarsi.arkphone.data.mms.MmsPart
import org.jarsi.arkphone.data.mms.composeSendReq
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeMessageNotifier
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(RobolectricTestRunner::class)
class MmsDownloaderTest {

    private lateinit var provider: FakeTelephonyProvider
    private lateinit var downloader: MmsDownloader
    private val blockedNumbers = FakeBlockedNumbersRepository()
    private val contacts = FakeContactsRepository()
    private val notifier = FakeMessageNotifier()
    private val repository = FakeMessagesRepository()

    /** The push fixture: an m-notification-ind from +358441234567. */
    private fun pushPdu(): ByteArray {
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        fun textBytes(text: String) = text.toByteArray(Charsets.UTF_8) + 0
        return bytes(0x8C, 0x82) +
            bytes(0x98) + textBytes("T1") +
            bytes(0x89, 0x19, 0x80) + textBytes("+358441234567/TYPE=PLMN") +
            bytes(0x83) + textBytes("http://mmsc/x")
    }

    @Before
    fun setUp() {
        provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms", provider)
        downloader = MmsDownloader(
            context = ApplicationProvider.getApplicationContext<Application>(),
            blockedNumbers = blockedNumbers,
            contactsRepository = contacts,
            messageNotifier = notifier,
            messagesRepository = repository,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `a push inserts the pending placeholder with its content location`() = runTest {
        downloader.onPush(pushPdu())

        val row = provider.mmsRows.single()
        assertEquals(130, row.getAsInteger("m_type"))
        assertEquals(42L, row.getAsLong("thread_id"))
        assertEquals(0, row.getAsInteger("read"))
        assertEquals("http://mmsc/x", row.getAsString("ct_l"))
        val (messageId, addr) = provider.mmsAddrRows.single()
        assertEquals(row.getAsLong("_id"), messageId)
        assertEquals("+358441234567", addr.getAsString("address"))
        assertEquals(137, addr.getAsInteger("type"))
        assertTrue(repository.refreshCalls > 0)
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `a push remembers the sim it arrived on`() = runTest {
        downloader.onPush(pushPdu(), subscriptionId = 7)

        assertEquals(7, provider.mmsRows.single().getAsInteger("sub_id"))
    }

    @Test
    fun `a re-sent notification for the same transaction is ignored`() = runTest {
        // An unacknowledged m-notification-ind gets re-pushed by the
        // carrier; the same transaction must not become a second copy.
        downloader.onPush(pushPdu())
        downloader.onPush(pushPdu())

        assertEquals(1, provider.mmsRows.size)
    }

    @Test
    fun `a finished download stores the parts and notifies`() = runTest {
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")
        val conf = composeSendReq(
            "+358441234567",
            listOf("+358400000000"),
            listOf(MmsPart("text/plain", "Kuvan saate".toByteArray(), null)),
        )
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(conf)
        }

        downloader.onDownloaded(messageId)

        val row = provider.mmsRows.single()
        assertEquals(132, row.getAsInteger("m_type"))
        val (partMessageId, part) = provider.mmsPartRows.single()
        assertEquals(messageId, partMessageId)
        assertEquals("text/plain", part.getAsString("ct"))
        assertEquals("Kuvan saate", part.getAsString("text"))
        with(notifier.notified.single()) {
            assertEquals(42L, threadId)
            assertEquals("Kuvan saate", body)
        }
    }

    @Test
    fun `a finished download notifies with the sim it arrived on`() = runTest {
        downloader.onPush(pushPdu(), subscriptionId = 7)
        val messageId = provider.mmsRows.single().getAsLong("_id")
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(
                composeSendReq(
                    "+358441234567",
                    listOf("+358400000000"),
                    listOf(MmsPart("text/plain", "Kuvan saate".toByteArray(), null)),
                ),
            )
        }

        downloader.onDownloaded(messageId)

        assertEquals(7, notifier.notified.single().subscriptionId)
    }

    @Test
    fun `a download finishing into an already read row does not notify`() = runTest {
        // The thread was open on screen when the content landed (or the user
        // tapped retry): the row is already read and a notification would
        // stick over the open conversation with nothing to cancel it.
        downloader.onPush(pushPdu())
        val row = provider.mmsRows.single()
        val messageId = row.getAsLong("_id")
        row.put("read", 1)
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(
                composeSendReq(
                    "+358441234567",
                    listOf("+358400000000"),
                    listOf(MmsPart("text/plain", "Luettu jo".toByteArray(), null)),
                ),
            )
        }

        downloader.onDownloaded(messageId)

        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `a blocked sender is stored read and never notified`() = runTest {
        blockedNumbers.blocked += "+358441234567"
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")
        assertEquals(1, provider.mmsRows.single().getAsInteger("read"))
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(
                composeSendReq(
                    "+358441234567",
                    listOf("+358400000000"),
                    listOf(MmsPart("text/plain", "Roskaa".toByteArray(), null)),
                ),
            )
        }

        downloader.onDownloaded(messageId)

        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `a failed download keeps the pending placeholder for retry`() = runTest {
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")

        downloader.onDownloaded(messageId)

        assertEquals(130, provider.mmsRows.single().getAsInteger("m_type"))
        assertTrue(provider.mmsPartRows.isEmpty())
        assertTrue(notifier.notified.isEmpty())
    }

    @Test
    fun `a group retrieve conf stores every other recipient as an addr row`() = runTest {
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(
                composeSendReq(
                    "+358441234567",
                    listOf("+358400000000", "+358411111111"),
                    listOf(MmsPart("text/plain", "Ryhmälle".toByteArray(), null)),
                ),
            )
        }

        downloader.onDownloaded(messageId)

        val addresses = provider.mmsAddrRows.map { it.second.getAsString("address") }
        assertTrue("+358400000000" in addresses)
        assertTrue("+358411111111" in addresses)
        assertEquals(132, provider.mmsRows.single().getAsInteger("m_type"))
        // The message moved from the sender's 1:1 thread to the group
        // thread (sender + both recipients — no own number is known here),
        // and the abandoned thread's unread flag was recomputed.
        assertEquals(78L, provider.mmsRows.single().getAsLong("thread_id"))
        assertEquals(listOf(42L), repository.recomputedThreads)
    }

    @Test
    @Config(sdk = [35])
    fun `the received group thread never counts this phone's own number`() = runTest {
        val subscriptionManager = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(SubscriptionManager::class.java)
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(
            listOf(
                ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                    .setId(1)
                    .buildSubscriptionInfo(),
            ),
        )
        shadowOf(subscriptionManager).setPhoneNumber(1, "0441111111")
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(
                composeSendReq(
                    "+358441234567",
                    listOf("0441111111", "+358400000000"),
                    listOf(MmsPart("text/plain", "Ryhmälle".toByteArray(), null)),
                ),
            )
        }

        downloader.onDownloaded(messageId)

        // The own number is filtered out, so the group is the sender plus
        // the one other recipient — the SAME thread the user's own reply to
        // this group creates. Counting ourselves in forked every group into
        // a parallel conversation.
        assertEquals(77L, provider.mmsRows.single().getAsLong("thread_id"))
    }

    @Test
    fun `a download on a dead subscription falls back to the default sms subscription`() {
        // The notification arrived on a SIM that has since been removed; a
        // retry pinned to the dead subscription id can never succeed.
        val subscriptionManager = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(SubscriptionManager::class.java)
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(
            listOf(
                ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                    .setId(1)
                    .buildSubscriptionInfo(),
            ),
        )
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)

        assertEquals(1, downloader.validSubscriptionOrDefault(7))
    }

    @Test
    fun `a download on a still active subscription keeps riding it`() {
        val subscriptionManager = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(SubscriptionManager::class.java)
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(
            listOf(
                ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                    .setId(7)
                    .buildSubscriptionInfo(),
            ),
        )
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(1)

        assertEquals(7, downloader.validSubscriptionOrDefault(7))
    }

    @Test
    fun `a retrieve conf without a sender still re-threads through the pushed one`() = runTest {
        // Some MMSCs deliver the retrieve-conf with the insert-address-token
        // From form; the push already stored the real sender on the row.
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(
                composeSendReq(
                    null,
                    listOf("+358400000000", "+358411111111"),
                    listOf(MmsPart("text/plain", "Ryhmälle".toByteArray(), null)),
                ),
            )
        }

        downloader.onDownloaded(messageId)

        assertEquals(78L, provider.mmsRows.single().getAsLong("thread_id"))
    }

    @Test
    fun `a group addressed through cc gets the group thread`() = runTest {
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        fun textBytes(text: String) = text.toByteArray(Charsets.UTF_8) + 0
        val headers = textBytes("text/plain")
        val data = "Ryhmälle".toByteArray()
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(
                bytes(0x8C, 0x84) +
                    bytes(0x89, 0x19, 0x80) + textBytes("+358441234567/TYPE=PLMN") +
                    bytes(0x97) + textBytes("+358400000000/TYPE=PLMN") +
                    bytes(0x82) + textBytes("+358411111111/TYPE=PLMN") +
                    bytes(0x84, 0xA3) +
                    bytes(0x01) +
                    bytes(headers.size, data.size) + headers + data,
            )
        }

        downloader.onDownloaded(messageId)

        // Sender + To + Cc make a three-member group thread; the Cc
        // recipient is stored on the row like any other.
        assertEquals(78L, provider.mmsRows.single().getAsLong("thread_id"))
        val addresses = provider.mmsAddrRows.map { it.second.getAsString("address") }
        assertTrue("+358411111111" in addresses)
    }

    @Test
    @Config(sdk = [35])
    fun `a lone recipient on a sim with an unknown number stays a 1 to 1 thread`() = runTest {
        // Dual SIM where only one subscription exposes its number: the To
        // entry is this phone's other SIM, but nothing can prove it. Partial
        // knowledge must fall back to the two-or-more heuristic instead of
        // minting a "group" out of {self, sender}.
        val subscriptionManager = ApplicationProvider.getApplicationContext<Application>()
            .getSystemService(SubscriptionManager::class.java)
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(
            listOf(
                ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                    .setId(1)
                    .buildSubscriptionInfo(),
                ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                    .setId(2)
                    .buildSubscriptionInfo(),
            ),
        )
        shadowOf(subscriptionManager).setPhoneNumber(1, "0441111111")
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(
                composeSendReq(
                    "+358441234567",
                    listOf("0407777777"),
                    listOf(MmsPart("text/plain", "Kahden kesken".toByteArray(), null)),
                ),
            )
        }

        downloader.onDownloaded(messageId)

        assertEquals(42L, provider.mmsRows.single().getAsLong("thread_id"))
    }

    @Test
    fun `a download that parses to zero parts keeps the placeholder too`() = runTest {
        downloader.onPush(pushPdu())
        val messageId = provider.mmsRows.single().getAsLong("_id")
        // m-retrieve-conf whose multipart body announces zero entries: a
        // content-less message row would only render as an empty bubble.
        val emptyConf = byteArrayOf(
            0x8C.toByte(), 0x84.toByte(), // message type: retrieve-conf
            0x84.toByte(), 0xA3.toByte(), // content type: multipart.mixed
            0x00, // zero body entries
        )
        downloader.downloadFileFor(messageId).apply {
            parentFile?.mkdirs()
            writeBytes(emptyConf)
        }

        downloader.onDownloaded(messageId)

        assertEquals(130, provider.mmsRows.single().getAsInteger("m_type"))
        assertTrue(provider.mmsPartRows.isEmpty())
        assertTrue(notifier.notified.isEmpty())
    }
}
