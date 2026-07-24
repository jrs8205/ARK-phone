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
import org.jarsi.arkphone.data.model.CallLogEntry
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
}

@HiltViewModel
class CallDetailViewModel @Inject constructor(
    private val callLogRepository: CallLogRepository,
    private val contactsRepository: ContactsRepository,
    private val blockedNumbersRepository: BlockedNumbersRepository,
) : ViewModel() {

    private val number = MutableStateFlow<String?>(null)
    private val contact = MutableStateFlow<ContactMatch?>(null)
    private val blocked = MutableStateFlow(false)
    private val canBlock = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val entries = number.filterNotNull().flatMapLatest { target ->
        callLogRepository.callLog().map { all ->
            all.filter { PhoneNumberUtils.compare(it.number, target) }
        }
    }

    val uiState: StateFlow<CallDetailUiState> = combine(
        number.filterNotNull(), entries, contact, blocked, canBlock,
    ) { target, matching, match, isBlocked, blockingAvailable ->
        CallDetailUiState(
            number = target,
            displayName = match?.displayName?.takeIf { it.isNotBlank() }
                ?: matching.firstOrNull { it.displayName != null }?.displayName,
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
        if (number.value == target) return
        number.value = target
        viewModelScope.launch {
            contact.value = contactsRepository.lookupContact(target)
            canBlock.value = blockedNumbersRepository.canBlock()
            blocked.value = blockedNumbersRepository.isBlocked(target)
        }
    }

    fun onToggleBlocked() {
        val target = number.value ?: return
        viewModelScope.launch {
            if (blocked.value) {
                if (blockedNumbersRepository.unblock(target)) blocked.value = false
            } else {
                if (blockedNumbersRepository.block(target)) blocked.value = true
            }
        }
    }

    fun onDeleteHistory() {
        val target = number.value ?: return
        viewModelScope.launch { callLogRepository.deleteCallsFor(target) }
    }
}
