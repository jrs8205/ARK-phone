package org.jarsi.arkphone.ui.contactcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.model.ContactDetails
import javax.inject.Inject

data class ContactCardUiState(
    val loading: Boolean = true,
    val details: ContactDetails? = null,
    val blocked: Boolean = false,
    val canBlock: Boolean = false,
)

@HiltViewModel
class ContactCardViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    private val blockedNumbersRepository: BlockedNumbersRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactCardUiState())
    val uiState: StateFlow<ContactCardUiState> = _uiState.asStateFlow()

    fun load(contactId: Long) {
        viewModelScope.launch {
            val details = contactsRepository.contactDetails(contactId)
            val firstNumber = details?.phones?.firstOrNull()?.value
            _uiState.value = ContactCardUiState(
                loading = false,
                details = details,
                blocked = firstNumber != null && blockedNumbersRepository.isBlocked(firstNumber),
                canBlock = firstNumber != null && blockedNumbersRepository.canBlock(),
            )
        }
    }

    fun onToggleBlocked() {
        val state = _uiState.value
        val numbers = state.details?.phones?.map { it.value }.orEmpty()
        if (numbers.isEmpty()) return
        viewModelScope.launch {
            if (state.blocked) {
                numbers.forEach { blockedNumbersRepository.unblock(it) }
                _uiState.value = _uiState.value.copy(blocked = false)
            } else {
                numbers.forEach { blockedNumbersRepository.block(it) }
                _uiState.value = _uiState.value.copy(blocked = true)
            }
        }
    }
}
