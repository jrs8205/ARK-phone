package org.jarsi.arkphone.ui.settings

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.telecom.CallScreeningRole
import org.jarsi.arkphone.data.model.BlockedCallAction
import org.jarsi.arkphone.data.model.SimAccount
import org.jarsi.arkphone.telecom.SpeechStatus
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.jarsi.arkphone.testing.FakeSimAccountRepository
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.jarsi.arkphone.util.NotificationAccessChecker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeSettingsRepository()

    private val simAccounts = FakeSimAccountRepository()

    private fun viewModel() = SettingsViewModel(
        repository,
        NotificationAccessChecker { false },
        object : CallScreeningRole {
            override fun isHeld() = false
            override fun requestIntent() = null
        },
        simAccounts,
        { SpeechStatus.READY },
        java.util.Optional.empty(),
    )

    @Test
    fun theCallSimChoiceIsStoredAndCleared() = runTest {
        val viewModel = viewModel()
        viewModel.onCallSimAccountChanged("sub-2")
        advanceUntilIdle()
        assertEquals("sub-2", repository.state.value.callSimAccountId)
        viewModel.onCallSimAccountChanged(null)
        advanceUntilIdle()
        assertEquals(null, repository.state.value.callSimAccountId)
    }

    @Test
    fun theBlockedCallActionIsStored() = runTest {
        val viewModel = viewModel()
        viewModel.onBlockedCallActionChanged(BlockedCallAction.VOICEMAIL)
        advanceUntilIdle()
        assertEquals(BlockedCallAction.VOICEMAIL, repository.state.value.blockedCallAction)
    }

    @Test
    fun theBlockingSimRestrictionIsStoredAndCleared() = runTest {
        val viewModel = viewModel()
        viewModel.onBlockingSimAccountChanged("sub-1")
        advanceUntilIdle()
        assertEquals("sub-1", repository.state.value.blockingSimAccountId)
        viewModel.onBlockingSimAccountChanged(null)
        advanceUntilIdle()
        assertEquals(null, repository.state.value.blockingSimAccountId)
    }

    @Test
    fun simAccountsAreLoadedForTheChooser() = runTest {
        simAccounts.accounts += SimAccount("sub-1", "DNA", null)
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals(listOf("DNA"), viewModel.simAccounts.value.map { it.label })
    }

    // uiState is never collected here, so it stays at its initial value —
    // exactly the stale-snapshot situation two quick edits can hit.

    @Test
    fun twoQuickPrefixAdditionsBothSurvive() = runTest {
        val viewModel = viewModel()
        viewModel.onAddBlockedPrefix("0700")
        viewModel.onAddBlockedPrefix("+358600")
        advanceUntilIdle()
        assertEquals(setOf("0700", "+358600"), repository.state.value.blockedPrefixes)
    }

    @Test
    fun twoQuickAllowedNumberAdditionsBothSurvive() = runTest {
        val viewModel = viewModel()
        viewModel.onAddAllowedNumber("0401234567")
        viewModel.onAddAllowedNumber("0501112223")
        advanceUntilIdle()
        assertEquals(
            setOf("0401234567", "0501112223"),
            repository.state.value.allowedNumbers,
        )
    }

    @Test
    fun removalOnlyTouchesTheGivenEntry() = runTest {
        val repository = this@SettingsViewModelTest.repository
        repository.addBlockedPrefix("0700")
        repository.addBlockedPrefix("+358600")
        repository.addAllowedNumber("0401234567")
        val viewModel = viewModel()
        viewModel.onRemoveBlockedPrefix("0700")
        viewModel.onRemoveAllowedNumber("0401234567")
        advanceUntilIdle()
        assertEquals(setOf("+358600"), repository.state.value.blockedPrefixes)
        assertEquals(emptySet<String>(), repository.state.value.allowedNumbers)
    }
}
