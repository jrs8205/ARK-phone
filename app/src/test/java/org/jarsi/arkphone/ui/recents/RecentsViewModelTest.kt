package org.jarsi.arkphone.ui.recents

import android.Manifest
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
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

    private fun entry(id: Long) = CallLogEntry(
        id = id, number = "0401234567", displayName = null,
        type = CallType.INCOMING, timestampMillis = 1000L * id, durationSeconds = 60,
    )

    @Test
    fun emitsEntriesFromRepository() = runTest {
        permissions.grant(Manifest.permission.READ_CALL_LOG)
        val viewModel = RecentsViewModel(repository, permissions)
        viewModel.uiState.test {
            assertTrue(awaitItem().loading)
            repository.entries.value = listOf(entry(1), entry(2))
            val state = awaitItem()
            assertFalse(state.loading)
            assertEquals(2, state.entries.size)
            assertTrue(state.hasPermission)
        }
    }

    @Test
    fun reportsMissingPermission() = runTest {
        val viewModel = RecentsViewModel(repository, permissions)
        viewModel.uiState.test {
            awaitItem()
            repository.entries.value = emptyList()
            // hasPermission comes from the checker, not the repository
            assertFalse(viewModel.uiState.value.hasPermission)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
