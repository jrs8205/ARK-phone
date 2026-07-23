package org.jarsi.arkphone.ui.dialpad

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

data class DialpadUiState(
    val number: String = "",
    val suggestions: List<Contact> = emptyList(),
)

@HiltViewModel
class DialpadViewModel @Inject constructor(
    contactsRepository: ContactsRepository,
) : ViewModel() {

    private val number = MutableStateFlow("")

    val uiState: StateFlow<DialpadUiState> =
        combine(contactsRepository.contacts(), number) { contacts, number ->
            DialpadUiState(
                number = number,
                suggestions = DialpadMatcher.filter(contacts, number).take(3),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DialpadUiState(),
        )

    fun onKey(digit: Char) {
        number.value += digit
    }

    fun onDelete() {
        number.value = number.value.dropLast(1)
    }

    fun onClear() {
        number.value = ""
    }

    fun setNumber(value: String) {
        number.value = value
    }
}
