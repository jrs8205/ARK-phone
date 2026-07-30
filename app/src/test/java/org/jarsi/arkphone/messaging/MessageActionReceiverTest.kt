package org.jarsi.arkphone.messaging

import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.testing.FakeMessageNotifier
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.jarsi.arkphone.testing.FakeSmsSender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageActionReceiverTest {

    private val fakeSender = FakeSmsSender()
    private val fakeRepository = FakeMessagesRepository()
    private val fakeNotifier = FakeMessageNotifier()

    private val handler = MessageActionHandler(
        smsSender = fakeSender,
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
