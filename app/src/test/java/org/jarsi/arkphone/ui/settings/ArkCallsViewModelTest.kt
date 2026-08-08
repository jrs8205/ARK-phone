package org.jarsi.arkphone.ui.settings

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.testing.FakeArkIdentityRepository
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.jarsi.arkphone.testing.FakeVoipAccountGateway
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.jarsi.arkphone.voip.ArkRegistration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class ArkCallsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val identities = FakeArkIdentityRepository()
    private val settings = FakeSettingsRepository()
    private val gateway = FakeVoipAccountGateway()

    private fun viewModel(available: Boolean = true) = ArkCallsViewModel(
        identityRepository = identities,
        settingsRepository = settings,
        accountGateway = if (available) Optional.of(gateway) else Optional.empty(),
    )

    @Test
    fun `a release build reports the feature as unavailable`() = runTest {
        val model = viewModel(available = false)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertFalse(model.uiState.value.available)
    }

    @Test
    fun `an unregistered device shows no code`() = runTest {
        val model = viewModel()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertTrue(model.uiState.value.available)
        assertNull(model.uiState.value.code)
    }

    @Test
    fun `registering stores the code and the device token`() = runTest {
        gateway.registration = ArkRegistration("ARK-7K3M-Q2FP", "token-abc")
        val model = viewModel()
        model.onNicknameChanged("Jarsi")
        model.onRegister()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-abc"),
            identities.state.value,
        )
        assertEquals("ARK-7K3M-Q2FP", model.uiState.value.code)
        assertFalse(model.uiState.value.registering)
        assertFalse(model.uiState.value.registerFailed)
    }

    @Test
    fun `a blank nickname never reaches the worker`() = runTest {
        val model = viewModel()
        model.onNicknameChanged("   ")
        model.onRegister()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertTrue(gateway.registerCalls.isEmpty())
        assertNull(identities.state.value)
    }

    @Test
    fun `a failed registration is shown and changes nothing`() = runTest {
        gateway.registration = null
        val model = viewModel()
        model.onNicknameChanged("Jarsi")
        model.onRegister()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertTrue(model.uiState.value.registerFailed)
        assertNull(identities.state.value)
    }

    @Test
    fun `the master switch is written through`() = runTest {
        val model = viewModel()
        model.onEnabledChanged(false)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertFalse(settings.state.value.arkInternetCallsEnabled)
        assertFalse(model.uiState.value.enabled)
    }
}
