package org.jarsi.arkphone.ui.detail

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallDetailViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun entry(id: Long, number: String) = CallLogEntry(
        id = id,
        number = number,
        displayName = null,
        type = CallType.INCOMING,
        timestampMillis = id * 1_000,
        durationSeconds = 10,
    )

    @Test
    fun filtersEntriesByNumberEquivalence() = runTest {
        val callLog = FakeCallLogRepository().apply {
            entries.value = listOf(
                entry(1, "+358401234567"),
                entry(2, "0401234567"),
                entry(3, "0409999999"),
            )
        }
        val viewModel = CallDetailViewModel(
            callLog,
            FakeContactsRepository(),
            FakeBlockedNumbersRepository(),
        )
        viewModel.setNumber("0401234567")
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.entries.size != 2) state = awaitItem()
            assertEquals(listOf(1L, 2L), state.entries.map { it.id })
        }
    }

    @Test
    fun resolvesContactAndBlockedState() = runTest {
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["0401234567"] = ContactMatch("Alice", "content://photo/1")
        }
        val blockedNumbers = FakeBlockedNumbersRepository().apply { blocked.add("0401234567") }
        val viewModel = CallDetailViewModel(FakeCallLogRepository(), contacts, blockedNumbers)
        viewModel.setNumber("0401234567")
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.displayName == null || !state.blocked) state = awaitItem()
            assertEquals("Alice", state.displayName)
            assertEquals("content://photo/1", state.photoUri)
            assertTrue(state.canBlock)
        }
    }

    @Test
    fun togglingBlockUpdatesTheBlockList() = runTest {
        val blockedNumbers = FakeBlockedNumbersRepository()
        val viewModel = CallDetailViewModel(
            FakeCallLogRepository(),
            FakeContactsRepository(),
            blockedNumbers,
        )
        viewModel.setNumber("0401234567")
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
            viewModel.onToggleBlocked()
            while (!state.blocked) state = awaitItem()
            assertTrue("0401234567" in blockedNumbers.blocked)
        }
    }

    @Test
    fun deleteHistoryDelegatesToTheRepository() = runTest {
        val callLog = FakeCallLogRepository()
        val viewModel = CallDetailViewModel(
            callLog,
            FakeContactsRepository(),
            FakeBlockedNumbersRepository(),
        )
        viewModel.setNumber("0401234567")
        viewModel.onDeleteHistory()
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading) state = awaitItem()
        }
        assertEquals(listOf("0401234567"), callLog.deletedNumbers)
    }
}
