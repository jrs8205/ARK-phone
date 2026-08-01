package org.jarsi.arkphone.ui.conversation

import android.content.Intent
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeMessageSharer
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.jarsi.arkphone.testing.FakeMmsSender
import org.jarsi.arkphone.testing.FakeSmsRole
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
class ConversationViewModelSelectionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeMessagesRepository()
    private val contacts = FakeContactsRepository()
    private val blockedNumbers = FakeBlockedNumbersRepository()
    private val smsSender = FakeSmsSender()
    private val sharer = FakeMessageSharer()

    private fun message(
        id: Long,
        timestamp: Long,
        isMms: Boolean = false,
        body: String = "Moro",
    ) = Message(
        id = id,
        threadId = 3,
        isMms = isMms,
        address = "+358441234567",
        body = body,
        timestampMillis = timestamp,
        incoming = true,
        status = MessageStatus.NONE,
        subscriptionId = 1,
    )

    private fun viewModel() = ConversationViewModel(
        repository,
        contacts,
        blockedNumbers,
        smsSender,
        FakeMmsSender(),
        FakeSmsRole(held = true),
        sharer,
        org.jarsi.arkphone.testing.FakeMessageNotifier(),
    )

    private fun seedThread(messages: List<Message>) {
        repository.messagesByThread
            .getOrPut(3L) { MutableStateFlow(emptyList()) }
            .value = messages
    }

    @Test
    fun togglingKeepsSmsAndMmsRowsWithTheSameIdApart() = runTest {
        val sms = message(4, 1000)
        val mms = message(4, 2000, isMms = true)
        seedThread(listOf(sms, mms))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.messages.size != 2) {
                state = awaitItem()
            }
            viewModel.onToggleMessageSelection(sms)
            state = awaitItem()
            assertEquals(setOf(MessageKey(4, false)), state.selectedMessageKeys)
            assertEquals(listOf(sms), state.selectedMessages)
            viewModel.onToggleMessageSelection(mms)
            state = awaitItem()
            assertEquals(setOf(MessageKey(4, false), MessageKey(4, true)), state.selectedMessageKeys)
            viewModel.onToggleMessageSelection(sms)
            state = awaitItem()
            assertEquals(setOf(MessageKey(4, true)), state.selectedMessageKeys)
        }
    }

    @Test
    fun deleteSelectedRemovesEachRowWithItsKindAndClears() = runTest {
        val sms = message(4, 1000)
        val mms = message(4, 2000, isMms = true)
        seedThread(listOf(sms, mms))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.messages.size != 2) {
                state = awaitItem()
            }
            viewModel.onToggleMessageSelection(sms)
            awaitItem()
            viewModel.onToggleMessageSelection(mms)
            awaitItem()
            viewModel.onDeleteSelected()
            state = awaitItem()
            while (state.selectedMessageKeys.isNotEmpty()) {
                state = awaitItem()
            }
            assertEquals(setOf(4L to false, 4L to true), repository.deletedMessages.toSet())
            assertTrue(repository.refreshCalls > 0)
        }
    }

    @Test
    fun shareSendsTheSelectionInTimestampOrderAndClears() = runTest {
        val early = message(1, 1000, body = "Eka")
        val late = message(2, 2000, body = "Toka")
        seedThread(listOf(early, late))
        sharer.intent = Intent(Intent.ACTION_SEND)
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.messages.size != 2) {
                state = awaitItem()
            }
            viewModel.onToggleMessageSelection(late)
            awaitItem()
            viewModel.onToggleMessageSelection(early)
            awaitItem()
            var shared: Intent? = null
            viewModel.onShareSelected { shared = it }
            state = awaitItem()
            while (state.selectedMessageKeys.isNotEmpty()) {
                state = awaitItem()
            }
            assertEquals(Intent.ACTION_SEND, shared?.action)
            assertEquals(listOf(early, late), sharer.requests.single())
        }
    }

    @Test
    fun shareWithNothingShareableKeepsTheSelection() = runTest {
        val target = message(1, 1000, body = "Eka")
        seedThread(listOf(target))
        sharer.intent = null
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.messages.isEmpty()) {
                state = awaitItem()
            }
            viewModel.onToggleMessageSelection(target)
            awaitItem()
            var shared: Intent? = null
            viewModel.onShareSelected { shared = it }
            advanceUntilIdle()
            assertNull(shared)
            assertEquals(setOf(MessageKey(1, false)), viewModel.uiState.value.selectedMessageKeys)
        }
    }

    @Test
    fun selectionDropsRowsThatDisappear() = runTest {
        val first = message(1, 1000)
        val second = message(2, 2000)
        seedThread(listOf(first, second))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.messages.size != 2) {
                state = awaitItem()
            }
            viewModel.onToggleMessageSelection(first)
            awaitItem()
            seedThread(listOf(second))
            state = awaitItem()
            while (state.messages.size != 1) {
                state = awaitItem()
            }
            assertTrue(state.selectedMessageKeys.isEmpty())
        }
    }
}
