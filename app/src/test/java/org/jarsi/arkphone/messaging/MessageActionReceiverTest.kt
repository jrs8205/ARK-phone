package org.jarsi.arkphone.messaging

import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.testing.FakeMessageNotifier
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.jarsi.arkphone.testing.FakeMmsSender
import org.jarsi.arkphone.testing.FakeSmsSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageActionReceiverTest {

    private val fakeSender = FakeSmsSender()
    private val fakeMmsSender = FakeMmsSender()
    private val fakeRepository = FakeMessagesRepository()
    private val fakeNotifier = FakeMessageNotifier()

    private val handler = MessageActionHandler(
        smsSender = fakeSender,
        mmsSender = fakeMmsSender,
        messagesRepository = fakeRepository,
        messageNotifier = fakeNotifier,
    )

    @Test
    fun `reply sends the text and marks the thread read`() = runTest {
        handler.handle(
            action = MessageActionReceiver.ACTION_MESSAGE_REPLY,
            threadId = 3L,
            address = "+358441234567",
            replyText = " Takaisin ",
        )

        assertEquals("+358441234567" to "Takaisin", fakeSender.sent.single())
        assertEquals(listOf(3L), fakeRepository.markedRead)
        assertEquals(listOf(3L), fakeNotifier.cancelledThreads)
    }

    @Test
    fun `a reply on a group thread goes to the whole group as one MMS`() = runTest {
        fakeRepository.recipientsByThread[3L] = listOf("+358441111111", "+358442222222")
        handler.handle(
            action = MessageActionReceiver.ACTION_MESSAGE_REPLY,
            threadId = 3L,
            address = "+358441111111",
            replyText = "Moro kaikille",
        )

        assertTrue(fakeSender.sent.isEmpty())
        val sent = fakeMmsSender.sent.single()
        assertEquals(listOf("+358441111111", "+358442222222"), sent.addresses)
        assertEquals("Moro kaikille", sent.text)
        assertEquals(listOf(3L), fakeRepository.markedRead)
        assertEquals(listOf(3L), fakeNotifier.cancelledThreads)
    }

    @Test
    fun `a group reply rides the sim the notification arrived on`() = runTest {
        fakeRepository.recipientsByThread[3L] = listOf("+358441111111", "+358442222222")
        handler.handle(
            action = MessageActionReceiver.ACTION_MESSAGE_REPLY,
            threadId = 3L,
            address = "+358441111111",
            replyText = "Moro",
            subscriptionId = 5,
        )

        assertEquals(5, fakeMmsSender.sent.single().subscriptionId)
    }

    @Test
    fun `a reply on a single thread stays a plain SMS`() = runTest {
        fakeRepository.recipientsByThread[3L] = listOf("+358441234567")
        handler.handle(
            action = MessageActionReceiver.ACTION_MESSAGE_REPLY,
            threadId = 3L,
            address = "+358441234567",
            replyText = "Takaisin",
        )

        assertTrue(fakeMmsSender.sent.isEmpty())
        assertEquals("+358441234567" to "Takaisin", fakeSender.sent.single())
    }

    @Test
    fun `mark read only marks and clears the notification`() = runTest {
        handler.handle(
            action = MessageActionReceiver.ACTION_MESSAGE_MARK_READ,
            threadId = 3L,
            address = "+358441234567",
            replyText = null,
        )

        assertTrue(fakeSender.sent.isEmpty())
        assertEquals(listOf(3L), fakeRepository.markedRead)
        assertEquals(listOf(3L), fakeNotifier.cancelledThreads)
    }

    @Test
    fun `a reply without text still clears the thread`() = runTest {
        handler.handle(
            action = MessageActionReceiver.ACTION_MESSAGE_REPLY,
            threadId = 3L,
            address = "+358441234567",
            replyText = null,
        )

        assertTrue(fakeSender.sent.isEmpty())
        assertEquals(listOf(3L), fakeRepository.markedRead)
    }

    @Test
    fun `an unknown action does nothing`() = runTest {
        handler.handle(
            action = "org.example.SOMETHING_ELSE",
            threadId = 3L,
            address = "+358441234567",
            replyText = "Moro",
        )

        assertTrue(fakeSender.sent.isEmpty())
        assertTrue(fakeRepository.markedRead.isEmpty())
    }

    @Test
    fun `the inline reply text is read from the remote input results`() {
        val intent = Intent(MessageActionReceiver.ACTION_MESSAGE_REPLY)
        RemoteInput.addResultsToIntent(
            arrayOf(RemoteInput.Builder(MessageActionReceiver.KEY_REPLY_TEXT).build()),
            intent,
            Bundle().apply {
                putCharSequence(MessageActionReceiver.KEY_REPLY_TEXT, "Takaisin")
            },
        )

        assertEquals("Takaisin", replyTextFrom(intent))
    }
}
