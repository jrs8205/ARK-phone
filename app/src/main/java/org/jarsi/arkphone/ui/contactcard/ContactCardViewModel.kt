package org.jarsi.arkphone.ui.contactcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.model.ContactDetails
import javax.inject.Inject

data class ContactCardUiState(
    val loading: Boolean = true,
    val details: ContactDetails? = null,
)

@HiltViewModel
class ContactCardViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactCardUiState())
    val uiState: StateFlow<ContactCardUiState> = _uiState.asStateFlow()

    fun load(contactId: Long) {
        viewModelScope.launch {
            _uiState.value = ContactCardUiState(
                loading = false,
                details = contactsRepository.contactDetails(contactId),
            )
        }
    }
}
