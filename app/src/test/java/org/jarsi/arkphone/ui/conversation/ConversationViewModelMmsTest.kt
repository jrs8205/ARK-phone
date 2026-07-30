package org.jarsi.arkphone.ui.conversation

import android.net.Uri
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.jarsi.arkphone.testing.FakeMessagingSims
import org.jarsi.arkphone.testing.FakeMmsSender
import org.jarsi.arkphone.testing.FakeSmsSender
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ConversationViewModelMmsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeMessagesRepository()
    private val contacts = FakeContactsRepository()
    private val blockedNumbers = FakeBlockedNumbersRepository()
    private val smsSender = FakeSmsSender()
    private val mmsSender = FakeMmsSender()
    private val messagingSims = FakeMessagingSims()

    private fun viewModel() = ConversationViewModel(
        repository,
        contacts,
        blockedNumbers,
        smsSender,
        mmsSender,
        messagingSims,
    )

    private fun seedThread() {
        repository.messagesByThread
            .getOrPut(3L) { MutableStateFlow(emptyList()) }
            .value = listOf(
            Message(
                id = 1,
                threadId = 3,
                isMms = false,
                address = "+358441234567",
                body = "Moro",
                timestampMillis = 1000,
                incoming = true,
                status = MessageStatus.NONE,
                subscriptionId = 1,
            ),
        )
    }

    @Test
    fun sendWithAnAttachmentRoutesToTheMmsSender() = runTest {
        seedThread()
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.address == null) {
                state = awaitItem()
            }
            viewModel.onAttachImage(Uri.parse("content://media/1"))
            state = awaitItem()
            assertEquals("content://media/1", state.attachedImageUri)
            viewModel.onSendText(" Saate ")
            advanceUntilIdle()
            with(mmsSender.sent.single()) {
                assertEquals("+358441234567", address)
                assertEquals("Saate", text)
                assertEquals(Uri.parse("content://media/1"), imageUri)
            }
            assertTrue(smsSender.sent.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(viewModel.uiState.value.attachedImageUri)
    }

    @Test
    fun anAttachmentAloneSendsWithoutText() = runTest {
        seedThread()
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.address == null) {
                state = awaitItem()
            }
            viewModel.onAttachImage(Uri.parse("content://media/2"))
            awaitItem()
            viewModel.onSendText("")
            advanceUntilIdle()
            assertNull(mmsSender.sent.single().text)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
