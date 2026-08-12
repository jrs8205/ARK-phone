package org.jarsi.arkphone.ui.conversation

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeMessageNotifier
import org.jarsi.arkphone.testing.FakeMessageSharer
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.jarsi.arkphone.testing.FakeMmsSender
import org.jarsi.arkphone.testing.FakeSmsRole
import org.jarsi.arkphone.testing.FakeSmsSender
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeMessagesRepository()
    private val contacts = FakeContactsRepository()
    private val blockedNumbers = FakeBlockedNumbersRepository()
    private val smsSender = FakeSmsSender()
    private val smsRole = FakeSmsRole(held = true)
    private val notifier = FakeMessageNotifier()

    private fun message(
        id: Long,
        timestamp: Long,
        incoming: Boolean = true,
        address: String = "+358441234567",
        body: String = "Moro",
        status: MessageStatus = MessageStatus.NONE,
        threadId: Long = 3,
    ) = Message(
        id = id,
        threadId = threadId,
        isMms = false,
        address = address,
        body = body,
        timestampMillis = timestamp,
        incoming = incoming,
        status = status,
        subscriptionId = 1,
    )

    private fun viewModel() = ConversationViewModel(
        repository,
        contacts,
        blockedNumbers,
        smsSender,
        FakeMmsSender(),
        smsRole,
        FakeMessageSharer(),
        notifier,
    )

    private fun seedThread(threadId: Long, messages: List<Message>) {
        repository.messagesByThread
            .getOrPut(threadId) { MutableStateFlow(emptyList()) }
            .value = messages
    }

    @Test
    fun openingAThreadCancelsItsNotification() = runTest {
        seedThread(3L, listOf(message(1, 1000)))
        val viewModel = viewModel()
        viewModel.open(3L)
        advanceUntilIdle()
        assertTrue(3L in notifier.cancelledThreads)
    }

    @Test
    fun viewingMessagesMarksTheThreadReadAndCancelsItsNotification() = runTest {
        seedThread(3L, listOf(message(1, 1000)))
        val viewModel = viewModel()
        viewModel.open(3L)
        advanceUntilIdle()
        notifier.cancelledThreads.clear()
        repository.markedRead.clear()
        viewModel.onMessagesViewed()
        advanceUntilIdle()
        assertTrue(3L in repository.markedRead)
        assertTrue(3L in notifier.cancelledThreads)
    }

    @Test
    fun messagesAcrossTwoDaysGetASeparatorEach() {
        val dayOne = 1_000_000_000_000L
        val dayThree = dayOne + 48 * 60 * 60 * 1000L
        val rows = dateSeparators(listOf(message(1, dayOne), message(2, dayThree)))
        assertEquals(
            listOf(
                ConversationRow.DaySeparator(dayOne),
                ConversationRow.MessageRow(message(1, dayOne)),
                ConversationRow.DaySeparator(dayThree),
                ConversationRow.MessageRow(message(2, dayThree)),
            ),
            rows,
        )
    }

    @Test
    fun sameDayRunGetsOneSeparator()  {
        val dayOne = 1_000_000_000_000L
        val rows = dateSeparators(listOf(message(1, dayOne), message(2, dayOne + 60_000)))
        assertEquals(
            listOf(
                ConversationRow.DaySeparator(dayOne),
                ConversationRow.MessageRow(message(1, dayOne)),
                ConversationRow.MessageRow(message(2, dayOne + 60_000)),
            ),
            rows,
        )
    }

    @Test
    fun emptyThreadProducesNoRows() {
        assertTrue(dateSeparators(emptyList()).isEmpty())
    }

    @Test
    fun openingThreadMarksItReadExactlyOnce() = runTest {
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.open(3L)
        advanceUntilIdle()
        assertEquals(listOf(3L), repository.markedRead)
    }

    @Test
    fun titleResolvesContactMatch() = runTest {
        seedThread(3L, listOf(message(1, 1000)))
        contacts.matchesByNumber["+358441234567"] =
            ContactMatch(displayName = "Matti", photoUri = "photo://x", contactId = 9L)
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.title != "Matti") {
                state = awaitItem()
            }
            assertEquals("photo://x", state.photoUri)
            assertEquals(9L, state.contactId)
            assertEquals("+358441234567", state.address)
        }
    }

    @Test
    fun groupTitleListsTheMembersNotTheOnlySendersCard() = runTest {
        repository.recipientsByThread[3L] = listOf("+358441234567", "+358400000000")
        seedThread(3L, listOf(message(1, 1000)))
        contacts.matchesByNumber["+358441234567"] =
            ContactMatch(displayName = "Matti", photoUri = "photo://x", contactId = 9L)
        val viewModel = viewModel()
        viewModel.open(3L)
        advanceUntilIdle()
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading) {
                state = awaitItem()
            }
            assertTrue(state.isGroup)
            assertEquals("+358441234567, +358400000000", state.title)
            assertEquals(null, state.photoUri)
            assertEquals(null, state.contactId)
        }
    }

    @Test
    fun blockedReflectsTheBlockList() = runTest {
        blockedNumbers.blocked += "+358441234567"
        seedThread(3L, listOf(message(1, 1000)))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (!state.blocked) {
                state = awaitItem()
            }
        }
    }

    @Test
    fun sendTextGoesToTheThreadAddressTrimmed() = runTest {
        seedThread(3L, listOf(message(1, 1000)))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.address == null) {
                state = awaitItem()
            }
            viewModel.onSendText(" Moro ")
            advanceUntilIdle()
            assertEquals("+358441234567" to "Moro", smsSender.sent.single())
            assertTrue(repository.refreshCalls > 0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun retryDiscardsTheFailedRowAndResends() = runTest {
        val failed = message(
            7,
            2000,
            incoming = false,
            body = "Aiemmin",
            status = MessageStatus.FAILED,
        )
        seedThread(3L, listOf(failed))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.onRetry(failed)
        advanceUntilIdle()
        assertEquals(listOf(7L), smsSender.discarded)
        assertEquals("+358441234567" to "Aiemmin", smsSender.sent.single())
    }

    @Test
    fun retryIgnoresFailedMmsRows() = runTest {
        // The retry path deletes and resends through the SMS sender; MMS ids
        // point at a different provider table.
        val failedMms = message(9, 2000, incoming = false, status = MessageStatus.FAILED)
            .copy(isMms = true)
        seedThread(3L, listOf(failedMms))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.onRetry(failedMms)
        advanceUntilIdle()
        assertTrue(smsSender.sent.isEmpty())
        assertTrue(smsSender.discarded.isEmpty())
    }

    @Test
    fun retryIgnoresMessagesThatDidNotFail() = runTest {
        val sent = message(8, 2000, incoming = false, status = MessageStatus.SENT)
        seedThread(3L, listOf(sent))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.onRetry(sent)
        advanceUntilIdle()
        assertTrue(smsSender.sent.isEmpty())
        assertTrue(smsSender.discarded.isEmpty())
    }

    @Test
    fun threadRecipientsBeatTheMessageRowsAsTheAddressSource() = runTest {
        // A thread whose only rows are sent MMS has blank message addresses;
        // the threads table still knows who the conversation is with.
        repository.recipientsByThread[3L] = listOf("+358441234567")
        seedThread(3L, listOf(message(1, 1000, address = "")))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.address == null) {
                state = awaitItem()
            }
            assertEquals("+358441234567", state.address)
            assertTrue(!state.isGroup)
        }
    }

    @Test
    fun aGroupThreadIsFlaggedAndTextGoesAsGroupMms() = runTest {
        repository.recipientsByThread[3L] = listOf("+358441234567", "+358400000000")
        seedThread(3L, listOf(message(1, 1000)))
        val mmsSender = FakeMmsSender()
        val viewModel = ConversationViewModel(
            repository,
            contacts,
            blockedNumbers,
            smsSender,
            mmsSender,
            smsRole,
            FakeMessageSharer(),
            notifier,
        )
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (!state.isGroup) {
                state = awaitItem()
            }
            viewModel.onSendText(" Moro kaikille ")
            advanceUntilIdle()
            with(mmsSender.sent.single()) {
                assertEquals(listOf("+358441234567", "+358400000000"), addresses)
                assertEquals("Moro kaikille", text)
                assertEquals(null, imageUri)
            }
            assertTrue(smsSender.sent.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aGroupReplyRidesTheSimTheGroupArrivedOn() = runTest {
        repository.recipientsByThread[3L] = listOf("+358441234567", "+358400000000")
        seedThread(3L, listOf(message(1, 1000).copy(subscriptionId = 5)))
        val mmsSender = FakeMmsSender()
        val viewModel = ConversationViewModel(
            repository,
            contacts,
            blockedNumbers,
            smsSender,
            mmsSender,
            smsRole,
            FakeMessageSharer(),
            notifier,
        )
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (!state.isGroup) {
                state = awaitItem()
            }
            viewModel.onSendText("Moro")
            advanceUntilIdle()
            assertEquals(5, mmsSender.sent.single().subscriptionId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sendingIsBlockedWithoutTheSmsRole() = runTest {
        smsRole.held = false
        seedThread(3L, listOf(message(1, 1000)))
        val viewModel = viewModel()
        viewModel.open(3L)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.address == null) {
                state = awaitItem()
            }
            assertTrue(!state.canSend)
            viewModel.onSendText("Moro")
            advanceUntilIdle()
            assertTrue(smsSender.sent.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteConversationForwardsAndSignals() = runTest {
        val viewModel = viewModel()
        viewModel.open(3L)
        var closed = false
        viewModel.onDeleteConversation { closed = true }
        advanceUntilIdle()
        assertEquals(listOf(3L), repository.deletedThreads)
        assertTrue(closed)
    }
}
