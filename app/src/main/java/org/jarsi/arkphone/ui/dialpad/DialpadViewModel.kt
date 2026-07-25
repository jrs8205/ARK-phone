package org.jarsi.arkphone.ui.dialpad

import android.telephony.PhoneNumberUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SpeedDialRepository
import org.jarsi.arkphone.data.model.Contact
import javax.inject.Inject

data class DialpadUiState(
    val number: String = "",
    val displayNumber: String = "",
    val suggestions: List<Contact> = emptyList(),
    val speedDial: Map<Int, String> = emptyMap(),
)

/** The typed number as shown: formatted for [countryIso], except for star/hash codes. */
internal fun formatDialpadNumber(number: String, countryIso: String): String {
    if (number.isBlank()) return number
    if (number.any { it == '*' || it == '#' }) return number
    return PhoneNumberUtils.formatNumber(number, countryIso) ?: number
}

private val PHONE_CHARACTERS = setOf('+', '*', '#')

@HiltViewModel
class DialpadViewModel @Inject constructor(
    contactsRepository: ContactsRepository,
    private val speedDialRepository: SpeedDialRepository,
) : ViewModel() {

    private val number = MutableStateFlow("")

    val uiState: StateFlow<DialpadUiState> =
        combine(
            contactsRepository.contacts(),
            number,
            speedDialRepository.entries,
        ) { contacts, number, speedDial ->
            DialpadUiState(
                number = number,
                displayNumber = formatDialpadNumber(number, Locale.getDefault().country),
                suggestions = DialpadMatcher.filter(contacts, number).take(3),
                speedDial = speedDial,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DialpadUiState(),
        )

    fun onPaste(text: String) {
        number.value += text.filter { it.isDigit() || it in PHONE_CHARACTERS }
    }

    fun saveSpeedDial(digit: Int, number: String) {
        viewModelScope.launch { speedDialRepository.set(digit, number) }
    }

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
