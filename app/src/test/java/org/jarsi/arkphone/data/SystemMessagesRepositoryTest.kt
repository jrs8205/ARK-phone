package org.jarsi.arkphone.data

import android.app.Application
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.data.model.MmsAttachment
import org.jarsi.arkphone.util.PermissionChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class SystemMessagesRepositoryTest {

    private lateinit var provider: FakeTelephonyProvider
    private lateinit var repository: SystemMessagesRepository
    private var hasReadSms = true

    @Before
    fun setUp() {
        provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        val context = ApplicationProvider.getApplicationContext<Application>()
        // The same instance answers for the sms and mms authorities too.
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms", provider)
        repository = SystemMessagesRepository(
            context = context,
            permissionChecker = object : PermissionChecker {
                override fun has(permission: String): Boolean = hasReadSms
            },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    @Test
    fun `conversations resolve recipient addresses and unread state`() = runTest {
        provider.canonicalAddresses[7L] = "+358441234567"
        provider.conversationRows += arrayOf<Any?>(3L, 1_722_000_000_000L, 5, "7", "Hei!", 0)
        val conversations = repository.conversations().first()
        assertEquals(1, conversations.size)
        with(conversations.single()) {
            assertEquals(3L, threadId)
            assertEquals(listOf("+358441234567"), addresses)
            assertEquals("Hei!", snippet)
            assertTrue(unread)
        }
    }

    @Test
    fun `group thread carries every address`() = runTest {
        provider.canonicalAddresses[1L] = "+358401111111"
        provider.canonicalAddresses[2L] = "+358402222222"
        provider.conversationRows += arrayOf<Any?>(9L, 1L, 2, "1 2", null, 1)
        assertEquals(
            listOf("+358401111111", "+358402222222"),
            repository.conversations().first().single().addresses,
        )
    }

    @Test
    fun `no permission yields empty list`() = runTest {
        hasReadSms = false
        provider.conversationRows += arrayOf<Any?>(3L, 1L, 1, "7", "x", 0)
        assertTrue(repository.conversations().first().isEmpty())
    }

    @Test
    fun `thread messages map status and direction`() = runTest {
        provider.smsRows += smsRow(
            id = 1, threadId = 3, address = "+358441234567", body = "Moro",
            date = 1000L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = Telephony.Sms.STATUS_NONE, subId = 1,
        )
        provider.smsRows += smsRow(
            id = 2, threadId = 3, address = "+358441234567", body = "Takaisin",
            date = 2000L, type = Telephony.Sms.MESSAGE_TYPE_SENT,
            status = Telephony.Sms.STATUS_COMPLETE, subId = 1,
        )
        provider.smsRows += smsRow(
            id = 9, threadId = 8, address = "+358400000000", body = "Toinen ketju",
            date = 1500L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = Telephony.Sms.STATUS_NONE, subId = 1,
        )
        val messages = repository.messages(3L).first()
        assertEquals(listOf("Moro", "Takaisin"), messages.map { it.body })
        assertTrue(messages[0].incoming)
        assertTrue(!messages[1].incoming)
        assertEquals(MessageStatus.DELIVERED, messages[1].status)
    }

    @Test
    fun `mark read updates only unread rows of the thread`() = runTest {
        repository.markThreadRead(3L)
        val (uri, values) = provider.updatedUris.first()
        assertEquals(Telephony.Sms.CONTENT_URI, uri)
        assertEquals(1, values.getAsInteger(Telephony.Sms.READ))
    }

    @Test
    fun `delete thread targets the conversations uri`() = runTest {
        assertTrue(provider.deletedUris.isEmpty())
        assertTrue(repository.deleteThread(3L))
        assertTrue(provider.deletedUris.single().toString().endsWith("/conversations/3"))
    }

    @Test
    fun `thread merges sms and mms rows by time`() = runTest {
        provider.smsRows += smsRow(
            id = 1, threadId = 3, address = "+358441234567", body = "Moro",
            date = 1000L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = Telephony.Sms.STATUS_NONE, subId = 1,
        )
        provider.mmsRows += mmsRow(id = 600, threadId = 3, dateSeconds = 2, mType = 132)
        provider.mmsPartRows += 600L to partValues(id = 901, ct = "text/plain", text = "MMS-teksti")
        provider.mmsPartRows += 600L to partValues(id = 902, ct = "image/jpeg")
        provider.mmsAddrRows += 600L to android.content.ContentValues().apply {
            put("address", "+358441234567")
            put("type", 137)
        }

        val messages = repository.messages(3L).first()

        assertEquals(listOf("Moro", "MMS-teksti"), messages.map { it.body })
        with(messages[1]) {
            assertTrue(isMms)
            assertTrue(incoming)
            assertEquals("+358441234567", address)
            assertEquals(2000L, timestampMillis)
            assertEquals(
                listOf(MmsAttachment("content://mms/part/902", "image/jpeg")),
                attachments,
            )
            assertTrue(!pendingDownload)
        }
    }

    @Test
    fun `an undownloaded mms is flagged for retry`() = runTest {
        provider.mmsRows += mmsRow(id = 601, threadId = 3, dateSeconds = 5, mType = 130)
        val message = repository.messages(3L).first().single()
        assertTrue(message.pendingDownload)
        assertTrue(message.isMms)
        assertEquals(null, message.body)
    }

    @Test
    fun `a retrieved inbox mms without any parts is flagged for retry`() = runTest {
        // A store that failed after the m_type update leaves a retrieved row
        // with no parts; with the content location intact the download can
        // be retried instead of showing an empty bubble.
        provider.mmsRows += mmsRow(id = 602, threadId = 3, dateSeconds = 5, mType = 132)
        val message = repository.messages(3L).first().single()
        assertTrue(message.pendingDownload)
    }

    @Test
    fun `an outgoing mms without stored parts is not flagged for retry`() = runTest {
        provider.mmsRows += mmsRow(
            id = 603, threadId = 3, dateSeconds = 5, mType = 132,
            msgBox = Telephony.Mms.MESSAGE_BOX_SENT,
        )
        val message = repository.messages(3L).first().single()
        assertTrue(!message.pendingDownload)
        assertEquals(MessageStatus.SENT, message.status)
    }

    @Test
    fun `recipients come from the threads table canonical addresses`() = runTest {
        provider.conversationRows += arrayOf<Any?>(3L, 1000L, 2, "11 12", "Moro", 1)
        provider.canonicalAddresses[11L] = "+358441234567"
        provider.canonicalAddresses[12L] = "+358400000000"

        assertEquals(
            listOf("+358441234567", "+358400000000"),
            repository.recipients(3L),
        )
        assertEquals(emptyList<String>(), repository.recipients(99L))
    }

    @Test
    fun `mark read touches both message tables`() = runTest {
        repository.markThreadRead(3L)
        val uris = provider.updatedUris.map { it.first }
        assertTrue(Telephony.Sms.CONTENT_URI in uris)
        assertTrue(Telephony.Mms.CONTENT_URI in uris)
    }

    private fun mmsRow(
        id: Long,
        threadId: Long,
        dateSeconds: Long,
        mType: Int,
        msgBox: Int = Telephony.Mms.MESSAGE_BOX_INBOX,
        read: Int = 1,
        subId: Int = 1,
    ): android.content.ContentValues = android.content.ContentValues().apply {
        put("_id", id)
        put("thread_id", threadId)
        put("date", dateSeconds)
        put("msg_box", msgBox)
        put("read", read)
        put("sub_id", subId)
        put("m_type", mType)
        put("ct_l", "http://mmsc/x")
    }

    private fun partValues(
        id: Long,
        ct: String,
        text: String? = null,
    ): android.content.ContentValues = android.content.ContentValues().apply {
        put("_id", id)
        put("ct", ct)
        text?.let { put("text", it) }
    }

    @Test
    fun `body search returns only threads whose messages match`() = runTest {
        provider.smsRows += smsRow(
            id = 1, threadId = 3, address = "+358441234567", body = "Moro vaan",
            date = 1000L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = Telephony.Sms.STATUS_NONE, subId = 1,
        )
        provider.smsRows += smsRow(
            id = 2, threadId = 8, address = "+358400000000", body = "Terve",
            date = 2000L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = Telephony.Sms.STATUS_NONE, subId = 1,
        )
        assertEquals(setOf(3L), repository.threadIdsMatchingBody("mor"))
    }

    @Test
    fun `body search treats like wildcards as literals`() = runTest {
        provider.smsRows += smsRow(
            id = 1, threadId = 3, address = "+358441234567", body = "Korko on 100%",
            date = 1000L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = Telephony.Sms.STATUS_NONE, subId = 1,
        )
        provider.smsRows += smsRow(
            id = 2, threadId = 8, address = "+358400000000", body = "Maksu 105 euroa",
            date = 2000L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = Telephony.Sms.STATUS_NONE, subId = 1,
        )
        assertEquals(setOf(3L), repository.threadIdsMatchingBody("0%"))
    }

    @Test
    fun `body search without permission is empty`() = runTest {
        hasReadSms = false
        provider.smsRows += smsRow(
            id = 1, threadId = 3, address = "+358441234567", body = "Moro",
            date = 1000L, type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = Telephony.Sms.STATUS_NONE, subId = 1,
        )
        assertTrue(repository.threadIdsMatchingBody("mor").isEmpty())
    }

    @Test
    fun `escape like query escapes the escape char and both wildcards`() {
        assertEquals("""a\%b\_c\\d""", escapeLikeQuery("""a%b_c\d"""))
    }

    private fun smsRow(
        id: Long,
        threadId: Long,
        address: String,
        body: String,
        date: Long,
        type: Int,
        status: Int,
        subId: Int,
    ): android.content.ContentValues = android.content.ContentValues().apply {
        put(Telephony.Sms._ID, id)
        put(Telephony.Sms.THREAD_ID, threadId)
        put(Telephony.Sms.ADDRESS, address)
        put(Telephony.Sms.BODY, body)
        put(Telephony.Sms.DATE, date)
        put(Telephony.Sms.TYPE, type)
        put(Telephony.Sms.STATUS, status)
        put(Telephony.Sms.READ, 1)
        put(Telephony.Sms.SUBSCRIPTION_ID, subId)
    }
}
