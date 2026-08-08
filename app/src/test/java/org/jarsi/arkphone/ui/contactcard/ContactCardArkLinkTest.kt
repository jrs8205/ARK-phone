package org.jarsi.arkphone.ui.contactcard

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.LabeledField
import org.jarsi.arkphone.testing.FakeArkLinkRepository
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeVoipAccountGateway
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Optional

class ContactCardArkLinkTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val contacts = FakeContactsRepository()
    private val blocked = FakeBlockedNumbersRepository()
    private val links = FakeArkLinkRepository()
    private val gateway = FakeVoipAccountGateway()

    private fun viewModel(available: Boolean = true) = ContactCardViewModel(
        contactsRepository = contacts,
        blockedNumbersRepository = blocked,
        arkLinkRepository = links,
        accountGateway = if (available) Optional.of(gateway) else Optional.empty(),
        clock = Clock { 5_000L },
    )

    private fun givenContact() {
        contacts.detailsById[1L] = ContactDetails(
            id = 1L,
            displayName = "Matti",
            photoUri = null,
            starred = false,
            phones = listOf(LabeledField("+358 44 5552841", null)),
        )
    }

    private fun runCurrent() = mainDispatcherRule.dispatcher.scheduler.runCurrent()

    @Test
    fun `a build without the engine hides the ark row`() = runTest {
        givenContact()
        val model = viewModel(available = false)
        model.load(1L)
        runCurrent()
        assertEquals(false, model.uiState.value.arkAvailable)
    }

    @Test
    fun `an invalid code never reaches the worker`() = runTest {
        givenContact()
        val model = viewModel()
        model.load(1L)
        runCurrent()
        model.onArkCodeEntered("not a code")
        runCurrent()
        assertTrue(gateway.lookUpCalls.isEmpty())
        assertEquals(ArkLinkError.INVALID_CODE, model.uiState.value.arkError)
    }

    @Test
    fun `an unknown code is reported`() = runTest {
        givenContact()
        gateway.account = null
        val model = viewModel()
        model.load(1L)
        runCurrent()
        model.onArkCodeEntered("ark-7k3m-q2fp")
        runCurrent()
        assertEquals(listOf("ARK-7K3M-Q2FP"), gateway.lookUpCalls)
        assertEquals(ArkLinkError.NOT_FOUND, model.uiState.value.arkError)
    }

    @Test
    fun `a found account is offered for confirmation and only then stored`() = runTest {
        givenContact()
        gateway.account = ArkAccount("ARK-7K3M-Q2FP", "Jarsi", "pk-test")
        val model = viewModel()
        model.load(1L)
        runCurrent()
        model.onArkCodeEntered("ARK-7K3M-Q2FP")
        runCurrent()
        assertEquals("Jarsi", model.uiState.value.arkPending?.nickname)
        assertTrue(links.state.value.isEmpty())
        model.onArkLinkConfirmed()
        runCurrent()
        val link = links.state.value.single()
        assertEquals("ARK-7K3M-Q2FP", link.code)
        assertEquals("Jarsi", link.nickname)
        assertEquals("pk-test", link.publicKey)
        assertEquals(5_000L, link.linkedAtMillis)
        assertEquals("Jarsi", model.uiState.value.arkLink?.nickname)
        assertNull(model.uiState.value.arkPending)
    }

    @Test
    fun `unlinking removes the row`() = runTest {
        givenContact()
        links.link("+358 44 5552841", "ARK-7K3M-Q2FP", "Jarsi", "pk", 1_000L)
        val model = viewModel()
        model.load(1L)
        runCurrent()
        assertEquals("Jarsi", model.uiState.value.arkLink?.nickname)
        model.onArkUnlink()
        runCurrent()
        assertTrue(links.state.value.isEmpty())
        assertNull(model.uiState.value.arkLink)
    }
}
