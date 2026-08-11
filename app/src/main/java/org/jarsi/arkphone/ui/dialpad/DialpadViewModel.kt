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
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SpeedDialRepository
import org.jarsi.arkphone.data.model.Contact
import javax.inject.Inject

data class DialpadUiState(
    val number: String = "",
    val displayNumber: String = "",
    val suggestions: List<Contact> = emptyList(),
    val historySuggestions: List<HistorySuggestion> = emptyList(),
    val speedDial: Map<Int, String> = emptyMap(),
)

/** A call-log number matching the typed prefix, offered like a contact hit. */
data class HistorySuggestion(val number: String, val display: String)

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
    callLogRepository: CallLogRepository,
) : ViewModel() {

    private val callLog = callLogRepository.callLog()

    private val number = MutableStateFlow("")

    val uiState: StateFlow<DialpadUiState> =
        combine(
            contactsRepository.contacts(),
            number,
            speedDialRepository.entries,
            callLog,
        ) { contacts, number, speedDial, log ->
            val country = Locale.getDefault().country
            DialpadUiState(
                number = number,
                displayNumber = formatDialpadNumber(number, country),
                suggestions = DialpadMatcher.filter(contacts, number).take(3),
                historySuggestions = DialpadMatcher
                    .filterHistory(log, contacts, number) {
                        PhoneNumberUtils.formatNumberToE164(it, country)
                    }
                    .take(3)
                    .map { HistorySuggestion(it, formatDialpadNumber(it, country)) },
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
