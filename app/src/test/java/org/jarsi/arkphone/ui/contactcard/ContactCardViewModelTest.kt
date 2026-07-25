package org.jarsi.arkphone.ui.contactcard

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.LabeledField
import org.jarsi.arkphone.testing.FakeBlockedNumbersRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ContactCardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val contacts = FakeContactsRepository().apply {
        detailsById[7] = ContactDetails(
            id = 7,
            displayName = "Matti Meikäläinen",
            photoUri = null,
            starred = false,
            phones = listOf(
                LabeledField("+358 44 5552841", "Mobile"),
                LabeledField("09 1234", "Home"),
            ),
        )
    }
    private val blockedNumbers = FakeBlockedNumbersRepository()

    private fun viewModel() = ContactCardViewModel(contacts, blockedNumbers)

    @Test
    fun loadReflectsTheBlockedState() = runTest {
        blockedNumbers.blocked += "+358 44 5552841"
        val viewModel = viewModel()
        viewModel.load(7)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.blocked)
        assertTrue(viewModel.uiState.value.canBlock)
    }

    @Test
    fun togglingBlocksEveryNumberOfTheContact() = runTest {
        val viewModel = viewModel()
        viewModel.load(7)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        viewModel.onToggleBlocked()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(setOf("+358 44 5552841", "09 1234"), blockedNumbers.blocked)
        assertTrue(viewModel.uiState.value.blocked)
        viewModel.onToggleBlocked()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(emptySet<String>(), blockedNumbers.blocked)
        assertFalse(viewModel.uiState.value.blocked)
    }
}
