package org.jarsi.arkphone.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.model.Contact
import javax.inject.Inject

/** The typed query as a dialable number, or null when it reads as a name. */
internal fun queryAsNumber(query: String): String? {
    val candidate = query.filterNot { it.isWhitespace() }
    if (candidate.isEmpty()) return null
    val validChars = candidate.all { it.isDigit() || it == '+' }
    val plusOnlyLeads = candidate.count { it == '+' } <= 1 && candidate.indexOf('+') <= 0
    val enoughDigits = candidate.count { it.isDigit() } >= 3
    return candidate.takeIf { validChars && plusOnlyLeads && enoughDigits }
}

data class NewMessageUiState(
    val loading: Boolean = true,
    val query: String = "",
    val contacts: List<Contact> = emptyList(),
    /** Non-null when the query itself can be messaged as a number. */
    val typedNumber: String? = null,
)

@HiltViewModel
class NewMessageViewModel @Inject constructor(
    contactsRepository: ContactsRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<NewMessageUiState> = combine(
        contactsRepository.contacts(),
        query,
    ) { contacts, query ->
        val visible = if (query.isBlank()) {
            contacts
        } else {
            contacts.filter { contact ->
                contact.displayName.contains(query, ignoreCase = true) ||
                    contact.phoneNumber?.contains(query) == true
            }
        }
        NewMessageUiState(
            loading = false,
            query = query,
            contacts = visible.filter { it.phoneNumber != null },
            typedNumber = queryAsNumber(query),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = NewMessageUiState(),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }
}
