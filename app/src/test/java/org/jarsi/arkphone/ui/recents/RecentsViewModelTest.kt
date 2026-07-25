package org.jarsi.arkphone.ui.recents

import android.Manifest
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.telecom.WhatsAppCallLauncher
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RecentsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeCallLogRepository()
    private val permissions = FakePermissionChecker()
    private val launchedCalls = mutableListOf<Pair<String?, String?>>()
    private val launcher = WhatsAppCallLauncher { number, name -> launchedCalls += number to name }

    private fun entry(
        id: Long,
        number: String = "0401234567",
        name: String? = null,
        type: CallType = CallType.INCOMING,
        source: CallSource = CallSource.PHONE,
    ) = CallLogEntry(
        id = id, number = number, displayName = name,
        type = type, timestampMillis = 1000L * id, durationSeconds = 60,
        source = source,
    )

    @Test
    fun whatsAppCallPassesTheNumberAndName() {
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        viewModel.onWhatsAppCall(
            entry(1).copy(number = "+358 44 5552841", displayName = "Matti"),
        )
        assertEquals(listOf<Pair<String?, String?>>("+358 44 5552841" to "Matti"), launchedCalls)
    }

    @Test
    fun whatsAppCallWithoutANumberPassesNull() {
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        viewModel.onWhatsAppCall(entry(1).copy(number = "", displayName = "Matti"))
        assertEquals(listOf<Pair<String?, String?>>(null to "Matti"), launchedCalls)
    }

    @Test
    fun emitsEntriesFromRepository() = runTest {
        permissions.grant(Manifest.permission.READ_CALL_LOG)
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        viewModel.uiState.test {
            assertTrue(awaitItem().loading)
            repository.entries.value = listOf(entry(2, "0501112223"), entry(1))
            val state = awaitItem()
            assertFalse(state.loading)
            assertEquals(2, state.entries.size)
            assertTrue(state.hasPermission)
        }
    }

    @Test
    fun searchMatchesNamesAndNormalizedNumbers() = runTest {
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        repository.entries.value = listOf(
            entry(3, "+358 44 5552841", "Matti Meikäläinen"),
            entry(2, "0401234567", "Liisa"),
            entry(1, "0501112223"),
        )
        viewModel.uiState.test {
            skipItems(1)
            viewModel.onQueryChange("matti")
            assertEquals(listOf(3L), awaitItem().entries.map { it.entry.id })
            viewModel.onQueryChange("0445552")
            assertEquals(listOf(3L), awaitItem().entries.map { it.entry.id })
            viewModel.onQueryChange("")
            assertEquals(3, awaitItem().entries.size)
        }
    }

    @Test
    fun missedFilterShowsOnlyMissedCalls() = runTest {
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        repository.entries.value = listOf(
            entry(3, type = CallType.MISSED),
            entry(2, "0501112223", type = CallType.OUTGOING),
        )
        viewModel.uiState.test {
            skipItems(1)
            viewModel.onFilterChange(RecentsFilter.MISSED)
            assertEquals(listOf(3L), awaitItem().entries.map { it.entry.id })
        }
    }

    @Test
    fun outgoingFilterShowsOnlyOutgoingCalls() = runTest {
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        repository.entries.value = listOf(
            entry(3, type = CallType.OUTGOING),
            entry(2, "0501112223", type = CallType.INCOMING),
        )
        viewModel.uiState.test {
            skipItems(1)
            viewModel.onFilterChange(RecentsFilter.OUTGOING)
            assertEquals(listOf(3L), awaitItem().entries.map { it.entry.id })
        }
    }

    @Test
    fun blockedFilterShowsOnlyBlockedCalls() = runTest {
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        repository.entries.value = listOf(
            entry(3, type = CallType.BLOCKED),
            entry(2, "0501112223"),
        )
        viewModel.uiState.test {
            skipItems(1)
            viewModel.onFilterChange(RecentsFilter.BLOCKED)
            assertEquals(listOf(3L), awaitItem().entries.map { it.entry.id })
        }
    }

    @Test
    fun whatsAppFilterShowsOnlyWhatsAppCalls() = runTest {
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        repository.entries.value = listOf(
            entry(3, source = CallSource.WHATSAPP),
            entry(2, "0501112223"),
        )
        viewModel.uiState.test {
            skipItems(1)
            viewModel.onFilterChange(RecentsFilter.WHATSAPP)
            assertEquals(listOf(3L), awaitItem().entries.map { it.entry.id })
        }
    }

    @Test
    fun consecutiveCallsFromTheSameCallerAreGrouped() {
        val grouped = groupConsecutiveCalls(
            listOf(
                entry(5),
                entry(4),
                entry(3, "0501112223"),
                entry(2),
            ),
        )
        assertEquals(listOf(5L, 3L, 2L), grouped.map { it.entry.id })
        assertEquals(listOf(2, 1, 1), grouped.map { it.count })
    }

    @Test
    fun numberlessWhatsAppRowsGroupByName() {
        val grouped = groupConsecutiveCalls(
            listOf(
                entry(5, "", "Matti", source = CallSource.WHATSAPP),
                entry(4, "", "Matti", source = CallSource.WHATSAPP),
                entry(3, "", "Liisa", source = CallSource.WHATSAPP),
            ),
        )
        assertEquals(listOf(2, 1), grouped.map { it.count })
    }

    @Test
    fun reportsMissingPermission() = runTest {
        val viewModel = RecentsViewModel(repository, permissions, launcher)
        viewModel.uiState.test {
            awaitItem()
            repository.entries.value = emptyList()
            // hasPermission comes from the checker, not the repository
            assertFalse(viewModel.uiState.value.hasPermission)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
