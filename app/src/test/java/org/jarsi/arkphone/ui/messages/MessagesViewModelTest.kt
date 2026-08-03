package org.jarsi.arkphone.ui.messages

import android.Manifest
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.Conversation
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeMessagesRepository
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.jarsi.arkphone.testing.FakeSmsRole
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
    private val smsRole = FakeSmsRole(held = true)

    private fun viewModel() = MessagesViewModel(repository, contacts, permissions, smsRole)

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
        val viewModel = viewModel()
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
        val viewModel = viewModel()
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
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            val item = awaitItem().conversations.single()
            assertTrue(item.isGroup)
            assertEquals("+358401111111, +358402222222", item.title)
        }
    }

    @Test
    fun queryMatchingOnlyMessageBodySurfacesThatConversation() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        repository.conversationsState.value = listOf(
            conversation(3, listOf("+358441234567")),
            conversation(4, listOf("+358400000000")),
        )
        repository.bodyMatches["kokous"] = setOf(4L)
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onQueryChange("kokous")
            var state = awaitItem()
            while (state.conversations.map { it.conversation.threadId } != listOf(4L)) {
                state = awaitItem()
            }
        }
    }

    @Test
    fun missingPermissionIsExposed() = runTest {
        val viewModel = viewModel()
        viewModel.uiState.test {
            assertFalse(awaitItem().hasReadSmsPermission)
        }
    }

    @Test
    fun aMissingSmsRoleIsExposedForTheBanner() = runTest {
        smsRole.held = false
        val viewModel = viewModel()
        viewModel.uiState.test {
            assertFalse(awaitItem().isDefaultSmsApp)
        }
    }

    @Test
    fun togglingARowSelectsAndUnselectsIt() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        repository.conversationsState.value = listOf(conversation(3, listOf("+358441234567")))
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onToggleSelection(3L)
            assertEquals(setOf(3L), awaitItem().selectedThreadIds)
            viewModel.onToggleSelection(3L)
            val state = awaitItem()
            assertTrue(state.selectedThreadIds.isEmpty())
            assertFalse(state.selectionActive)
        }
    }

    @Test
    fun selectAllSelectsOnlyTheVisibleConversations() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        contacts.allContacts.value = listOf(contact("Matti", "+358441234567"))
        repository.conversationsState.value = listOf(
            conversation(3, listOf("+358441234567")),
            conversation(4, listOf("+358400000000")),
        )
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onQueryChange("mat")
            var state = awaitItem()
            while (state.conversations.size != 1) {
                state = awaitItem()
            }
            viewModel.onSelectAll()
            assertEquals(setOf(3L), awaitItem().selectedThreadIds)
        }
    }

    @Test
    fun selectAllClearsTheSelectionWhenEverythingIsAlreadySelected() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        repository.conversationsState.value = listOf(
            conversation(3, listOf("+358441234567")),
            conversation(4, listOf("+358400000000")),
        )
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onToggleSelection(3L)
            awaitItem()
            viewModel.onSelectAll()
            assertEquals(setOf(3L, 4L), awaitItem().selectedThreadIds)
            viewModel.onSelectAll()
            val state = awaitItem()
            assertTrue(state.selectedThreadIds.isEmpty())
            assertFalse(state.selectionActive)
        }
    }

    @Test
    fun deleteSelectedRemovesEachThreadAndClearsTheSelection() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        repository.conversationsState.value = listOf(
            conversation(3, listOf("+358441234567")),
            conversation(4, listOf("+358400000000")),
        )
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onToggleSelection(3L)
            awaitItem()
            viewModel.onToggleSelection(4L)
            awaitItem()
            viewModel.onDeleteSelected()
            var state = awaitItem()
            while (state.selectedThreadIds.isNotEmpty()) {
                state = awaitItem()
            }
            assertEquals(setOf(3L, 4L), repository.deletedThreads.toSet())
            assertTrue(repository.refreshCalls > 0)
        }
    }

    @Test
    fun selectionDropsConversationsThatDisappear() = runTest {
        permissions.grant(Manifest.permission.READ_SMS)
        repository.conversationsState.value = listOf(
            conversation(3, listOf("+358441234567")),
            conversation(4, listOf("+358400000000")),
        )
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            awaitItem()
            viewModel.onToggleSelection(3L)
            awaitItem()
            repository.conversationsState.value = listOf(conversation(4, listOf("+358400000000")))
            val state = awaitItem()
            assertTrue(state.selectedThreadIds.isEmpty())
        }
    }

    @Test
    fun refreshPicksUpAFreshlyGrantedRole() = runTest {
        smsRole.held = false
        permissions.grant(Manifest.permission.READ_SMS)
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            assertFalse(awaitItem().isDefaultSmsApp)
            smsRole.held = true
            viewModel.refreshPermissionState()
            var state = awaitItem()
            while (!state.isDefaultSmsApp) {
                state = awaitItem()
            }
        }
    }
}
