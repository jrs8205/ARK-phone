package org.jarsi.arkphone.ui.detail

import android.telephony.PhoneNumberUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.WhatsAppCallLogRepository
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.ContactMatch
import javax.inject.Inject

data class CallStats(
    val total: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val totalDurationSeconds: Long,
    val latestMillis: Long?,
)

fun computeCallStats(entries: List<CallLogEntry>): CallStats = CallStats(
    total = entries.size,
    incoming = entries.count { it.type == CallType.INCOMING },
    outgoing = entries.count { it.type == CallType.OUTGOING },
    missed = entries.count { it.type == CallType.MISSED },
    totalDurationSeconds = entries.sumOf { it.durationSeconds },
    latestMillis = entries.maxOfOrNull { it.timestampMillis },
)

data class CallDetailUiState(
    val number: String = "",
    val displayName: String? = null,
    val photoUri: String? = null,
    val blocked: Boolean = false,
    val canBlock: Boolean = false,
    val entries: List<CallLogEntry> = emptyList(),
    val loading: Boolean = true,
) {
    val stats: CallStats get() = computeCallStats(entries)
    val hasWhatsAppCalls: Boolean get() = entries.any { it.source == CallSource.WHATSAPP }

    /** False for a name-only WhatsApp caller — phone actions are hidden then. */
    val hasNumber: Boolean get() = number.isNotBlank()
}

@HiltViewModel
class CallDetailViewModel @Inject constructor(
    private val callLogRepository: CallLogRepository,
    private val contactsRepository: ContactsRepository,
    private val blockedNumbersRepository: BlockedNumbersRepository,
    private val whatsAppCallLogRepository: WhatsAppCallLogRepository,
) : ViewModel() {

    /** What the detail view is about: a phone number, or — for WhatsApp
     *  callers whose number could not be safely resolved — a bare name.
     *  A name key never coalesces with numbered rows: those belong to the
     *  number's own detail view. */
    private sealed interface DetailKey
    private data class NumberKey(val number: String) : DetailKey
    private data class NameKey(val name: String) : DetailKey

    private val key = MutableStateFlow<DetailKey?>(null)
    private val contact = MutableStateFlow<ContactMatch?>(null)
    private val blocked = MutableStateFlow(false)
    private val canBlock = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val entries = key.filterNotNull().flatMapLatest { target ->
        callLogRepository.callLog().map { all ->
            when (target) {
                is NumberKey -> all.filter { PhoneNumberUtils.compare(it.number, target.number) }
                is NameKey -> all.filter {
                    it.source == CallSource.WHATSAPP && it.number.isBlank() &&
                        it.displayName == target.name
                }
            }
        }
    }

    val uiState: StateFlow<CallDetailUiState> = combine(
        key.filterNotNull(), entries, contact, blocked, canBlock,
    ) { target, matching, match, isBlocked, blockingAvailable ->
        CallDetailUiState(
            number = (target as? NumberKey)?.number.orEmpty(),
            displayName = when (target) {
                is NameKey -> target.name
                is NumberKey -> match?.displayName?.takeIf { it.isNotBlank() }
                    ?: matching.firstOrNull { it.displayName != null }?.displayName
            },
            photoUri = match?.photoUri,
            blocked = isBlocked,
            canBlock = blockingAvailable,
            entries = matching,
            loading = false,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CallDetailUiState(),
    )

    fun setNumber(target: String) {
        if (key.value == NumberKey(target)) return
        key.value = NumberKey(target)
        viewModelScope.launch {
            contact.value = contactsRepository.lookupContact(target)
            canBlock.value = blockedNumbersRepository.canBlock()
            blocked.value = blockedNumbersRepository.isBlocked(target)
        }
    }

    fun setNameKey(name: String) {
        if (key.value == NameKey(name)) return
        key.value = NameKey(name)
    }

    fun onToggleBlocked() {
        val target = (key.value as? NumberKey)?.number ?: return
        viewModelScope.launch {
            if (blocked.value) {
                if (blockedNumbersRepository.unblock(target)) blocked.value = false
            } else {
                if (blockedNumbersRepository.block(target)) blocked.value = true
            }
        }
    }

    fun onDeleteHistory() {
        when (val target = key.value) {
            is NumberKey -> viewModelScope.launch { callLogRepository.deleteCallsFor(target.number) }
            is NameKey -> viewModelScope.launch {
                whatsAppCallLogRepository.deleteCallsForName(target.name)
            }
            null -> Unit
        }
    }
}
