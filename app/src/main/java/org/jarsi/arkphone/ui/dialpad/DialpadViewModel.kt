package org.jarsi.arkphone.ui.dialpad

import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SpeedDialRepository
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.di.DefaultDispatcher
import org.jarsi.arkphone.util.DialingRegion

data class DialpadUiState(
    val suggestions: List<Contact> = emptyList(),
    val historySuggestions: List<HistorySuggestion> = emptyList(),
    val speedDial: Map<Int, String> = emptyMap(),
)

/** A call-log number matching the typed prefix, offered like a contact hit. */
data class HistorySuggestion(val number: String, val display: String)

// External numbers may contain vanity letters, pauses or waits. Remove
// visual separators without changing the dial sequence handed to Telecom.
internal fun Char.isDialSeparator() = isWhitespace() || category == CharCategory.DASH_PUNCTUATION || this in "()./"

/** The typed number as shown: formatted for [countryIso], except for star/hash codes. */
internal fun formatDialpadNumber(number: String, countryIso: String): String {
    if (number.isBlank()) return number
    if (number.any { it == '*' || it == '#' }) return number
    return PhoneNumberUtils.formatNumber(number, countryIso) ?: number
}

@HiltViewModel
class DialpadViewModel @Inject constructor(
    contactsRepository: ContactsRepository,
    private val speedDialRepository: SpeedDialRepository,
    callLogRepository: CallLogRepository,
    private val dialingRegion: DialingRegion,
    @DefaultDispatcher private val matchDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val callLog = callLogRepository.callLog()

    val field = TextFieldState()
    val countryIso: String get() = dialingRegion.countryIso()
    private val number = snapshotFlow { field.text.toString() }

    private data class Suggestions(
        val contacts: List<Contact> = emptyList(),
        val history: List<HistorySuggestion> = emptyList(),
    )

    /** Matching times libphonenumber over the whole call log, so it runs off
     *  the main thread; mapLatest drops a computation the next keystroke has
     *  already outdated. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val suggestions =
        combine(
            contactsRepository.contacts(),
            number,
            callLog,
        ) { contacts, typed, log ->
            Triple(contacts, typed, log)
        }.mapLatest { (contacts, typed, log) ->
            val country = dialingRegion.countryIso()
            val e164 = { candidate: String ->
                PhoneNumberUtils.formatNumberToE164(candidate, country)
            }
            Suggestions(
                contacts = DialpadMatcher.filter(contacts, typed, e164).take(3),
                history = DialpadMatcher
                    .filterHistory(log, contacts, typed, e164)
                    .take(3)
                    .map { HistorySuggestion(it, formatDialpadNumber(it, country)) },
            )
        }.flowOn(matchDispatcher)
            // The editor echoes immediately. Seed the other controls too, so
            // returning to the tab never waits for the first call-log result.
            .onStart { emit(Suggestions()) }

    val uiState: StateFlow<DialpadUiState> =
        combine(
            speedDialRepository.entries,
            suggestions,
        ) { speedDial, matched ->
            DialpadUiState(
                suggestions = matched.contacts,
                historySuggestions = matched.history,
                speedDial = speedDial,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = DialpadUiState(),
        )

    fun saveSpeedDial(digit: Int, number: String) {
        viewModelScope.launch { speedDialRepository.set(digit, number) }
    }

    fun onKey(digit: Char) {
        field.edit {
            val start = selection.min
            replace(start, selection.max, digit.toString())
            selection = TextRange(start + 1)
        }
    }

    fun onDelete() {
        field.edit {
            val start = if (selection.collapsed) (selection.start - 1).coerceAtLeast(0) else selection.min
            delete(start, selection.max)
            selection = TextRange(start)
        }
    }

    fun onClear() {
        field.clearText()
    }

    fun setNumber(value: String) {
        field.setTextAndPlaceCursorAtEnd(value.filterNot(Char::isDialSeparator))
    }
}
