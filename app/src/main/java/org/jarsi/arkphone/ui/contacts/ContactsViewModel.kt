package org.jarsi.arkphone.ui.contacts

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

data class ContactsUiState(
    val loading: Boolean = true,
    val favorites: List<Contact> = emptyList(),
    val frequent: List<Contact> = emptyList(),
    val others: List<Contact> = emptyList(),
    val query: String = "",
    val hasPermission: Boolean = true,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactsRepository,
    private val callLogRepository: CallLogRepository,
    private val permissionChecker: PermissionChecker,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val permissionState = MutableStateFlow(hasContactsPermission())

    val uiState: StateFlow<ContactsUiState> =
        combine(
            repository.contacts(),
            callLogRepository.callLog(),
            query,
            permissionState,
        ) { contacts, log, query, hasPermission ->
            val visible = if (query.isBlank()) {
                contacts
            } else {
                contacts.filter { contact ->
                    contact.displayName.contains(query, ignoreCase = true) ||
                        contact.phoneNumber?.contains(query) == true
                }
            }
            ContactsUiState(
                loading = false,
                favorites = visible.filter { it.starred },
                frequent = if (query.isBlank()) frequentContacts(contacts, log) else emptyList(),
                others = visible.filterNot { it.starred },
                query = query,
                hasPermission = hasPermission,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ContactsUiState(loading = true, hasPermission = hasContactsPermission()),
        )

    fun onQueryChange(query: String) {
        this.query.value = query
    }

    fun refreshPermissionState() {
        permissionState.value = hasContactsPermission()
        repository.refresh()
        callLogRepository.refresh()
    }

    private fun hasContactsPermission() = permissionChecker.has(Manifest.permission.READ_CONTACTS)
}
