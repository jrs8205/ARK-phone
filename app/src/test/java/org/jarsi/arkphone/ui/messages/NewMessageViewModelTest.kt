package org.jarsi.arkphone.ui.messages

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class NewMessageViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val contacts = FakeContactsRepository()

    private fun contact(id: Long, name: String, number: String) = Contact(
        id = id,
        displayName = name,
        phoneNumber = number,
        photoUri = null,
        starred = false,
    )

    @Test
    fun aPlainNumberQueryIsUsableAsARecipient() {
        assertEquals("0445551234", queryAsNumber("0445551234"))
        assertEquals("+358441234567", queryAsNumber("+358 44 1234567"))
    }

    @Test
    fun namesAndTooShortNumbersAreNot() {
        assertNull(queryAsNumber("Matti"))
        assertNull(queryAsNumber("+35"))
        assertNull(queryAsNumber("04x5"))
        assertNull(queryAsNumber(""))
    }

    @Test
    fun queryFiltersContactsByNameOrNumber() = runTest {
        contacts.allContacts.value = listOf(
            contact(1, "Matti", "+358441234567"),
            contact(2, "Teppo", "+358400000000"),
        )
        val viewModel = NewMessageViewModel(contacts)
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onQueryChange("mat")
            val state = awaitItem()
            assertEquals(listOf("Matti"), state.contacts.map { it.displayName })
            assertNull(state.typedNumber)
        }
    }

    @Test
    fun aNumberQueryOffersTheTypedNumberRow() = runTest {
        contacts.allContacts.value = listOf(contact(1, "Matti", "+358441234567"))
        val viewModel = NewMessageViewModel(contacts)
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onQueryChange("0445551234")
            val state = awaitItem()
            assertEquals("0445551234", state.typedNumber)
        }
    }
}
