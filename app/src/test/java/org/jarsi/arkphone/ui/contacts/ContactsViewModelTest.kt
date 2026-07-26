package org.jarsi.arkphone.ui.contacts

import android.Manifest
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ContactsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = FakeContactsRepository()
    private val permissions = FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) }

    private fun contact(id: Long, name: String, starred: Boolean = false) = Contact(
        id = id, displayName = name, phoneNumber = "040$id", photoUri = null, starred = starred,
    )

    @Test
    fun separatesFavoritesFromOthers() = runTest {
        val viewModel = ContactsViewModel(repository, FakeCallLogRepository(), permissions)
        viewModel.uiState.test {
            awaitItem()
            repository.allContacts.value = listOf(
                contact(1, "Alice", starred = true),
                contact(2, "Bob"),
            )
            val state = awaitItem()
            assertEquals(listOf("Alice"), state.favorites.map { it.displayName })
            assertEquals(listOf("Bob"), state.others.map { it.displayName })
        }
    }

    @Test
    fun refreshPermissionStateAlsoRefreshesBothRepositories() {
        val callLog = FakeCallLogRepository()
        val viewModel = ContactsViewModel(repository, callLog, permissions)
        viewModel.refreshPermissionState()
        assertEquals(1, repository.refreshCalls)
        assertEquals(1, callLog.refreshCalls)
    }

    @Test
    fun filtersByQuery() = runTest {
        repository.allContacts.value = listOf(contact(1, "Alice"), contact(2, "Bob"))
        val viewModel = ContactsViewModel(repository, FakeCallLogRepository(), permissions)
        viewModel.onQueryChange("ali")
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.loading || state.others.size != 1) state = awaitItem()
            assertEquals(listOf("Alice"), state.others.map { it.displayName })
        }
    }
}
