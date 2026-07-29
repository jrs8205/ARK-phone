package org.jarsi.arkphone.ui.messages

import android.Manifest
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.Conversation
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MessagesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeMessagesRepository()
    private val contacts = FakeContactsRepository()
    private val permissions = FakePermissionChecker()

    private fun conversation(
        threadId: Long,
        addresses: List<String>,
        snippet: String? = "moi",
        unread: Boolean = false,
    ) = Conversation(
        threadId = threadId,
        addresses = addresses,
        snippet = snippet,
        timestampMillis = threadId * 1000,
        unread = unread,
    )

    private fun contact(name: String, number: String) = Contact(
        id = 1,
        displayName = name,
        phoneNumber = number,
        photoUri = null,
        starred = false,
    )

    @Test
    fun titleResolvesContactNameAcrossNumberForms() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        contacts.allContacts.value = listOf(contact("Matti", "+358 44 1234567"))
        repository.conversationsState.value = listOf(conversation(3, listOf("0441234567")))
        val viewModel = MessagesViewModel(repository, contacts, permissions)
        viewModel.uiState.test {
            skipItems(1)
            val state = awaitItem()
            assertEquals("Matti", state.conversations.single().title)
            assertFalse(state.conversations.single().isGroup)
        }
    }

    @Test
    fun queryFiltersByTitle() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        contacts.allContacts.value = listOf(contact("Matti", "+358441234567"))
        repository.conversationsState.value = listOf(
            conversation(3, listOf("+358441234567")),
            conversation(4, listOf("+358400000000")),
        )
        val viewModel = MessagesViewModel(repository, contacts, permissions)
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onQueryChange("mat")
            val state = awaitItem()
            assertEquals(listOf(3L), state.conversations.map { it.conversation.threadId })
        }
    }

    @Test
    fun groupThreadIsFlagged() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        repository.conversationsState.value = listOf(
            conversation(9, listOf("+358401111111", "+358402222222")),
        )
        val viewModel = MessagesViewModel(repository, contacts, permissions)
        viewModel.uiState.test {
            skipItems(1)
            val item = awaitItem().conversations.single()
            assertTrue(item.isGroup)
            assertEquals("+358401111111, +358402222222", item.title)
        }
    }

    @Test
    fun missingPermissionIsExposed() = runTest {
        val viewModel = MessagesViewModel(repository, contacts, permissions)
        viewModel.uiState.test {
            assertFalse(awaitItem().hasReadSmsPermission)
        }
    }
}
