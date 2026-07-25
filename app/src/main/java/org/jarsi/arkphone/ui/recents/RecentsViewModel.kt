package org.jarsi.arkphone.ui.recents

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
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.telecom.WhatsAppCallLauncher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

enum class RecentsFilter { ALL, MISSED, OUTGOING, WHATSAPP }

/** One visible log row: the newest call of a consecutive same-caller run. */
data class GroupedCallLogEntry(val entry: CallLogEntry, val count: Int = 1)

data class RecentsUiState(
    val loading: Boolean = true,
    val entries: List<GroupedCallLogEntry> = emptyList(),
    val hasPermission: Boolean = true,
    val query: String = "",
    val filter: RecentsFilter = RecentsFilter.ALL,
)

internal fun groupConsecutiveCalls(entries: List<CallLogEntry>): List<GroupedCallLogEntry> {
    val grouped = mutableListOf<GroupedCallLogEntry>()
    for (entry in entries) {
        val previous = grouped.lastOrNull()
        if (previous != null && callerKey(previous.entry) == callerKey(entry)) {
            grouped[grouped.lastIndex] = previous.copy(count = previous.count + 1)
        } else {
            grouped += GroupedCallLogEntry(entry)
        }
    }
    return grouped
}

private fun callerKey(entry: CallLogEntry): Pair<CallSource, String> =
    entry.source to entry.number.ifBlank { entry.displayName ?: "#${entry.id}" }

internal fun matchesCallQuery(entry: CallLogEntry, query: String): Boolean {
    if (query.isBlank()) return true
    if (entry.displayName?.contains(query, ignoreCase = true) == true) return true
    val queryDigits = query.filter { it.isDigit() }
    if (queryDigits.isEmpty()) return false
    val numberDigits = entry.number.filter { it.isDigit() }
    // "0445..." must also find "+358 44 5..." — retry without the trunk zero.
    return numberDigits.contains(queryDigits) ||
        (queryDigits.startsWith("0") && numberDigits.contains(queryDigits.drop(1)))
}

private fun matchesFilter(entry: CallLogEntry, filter: RecentsFilter): Boolean = when (filter) {
    RecentsFilter.ALL -> true
    RecentsFilter.MISSED -> entry.type == CallType.MISSED
    RecentsFilter.OUTGOING -> entry.type == CallType.OUTGOING
    RecentsFilter.WHATSAPP -> entry.source == CallSource.WHATSAPP
}

@HiltViewModel
class RecentsViewModel @Inject constructor(
    repository: CallLogRepository,
    private val permissionChecker: PermissionChecker,
    private val whatsAppCallLauncher: WhatsAppCallLauncher,
) : ViewModel() {

    private val permissionState = MutableStateFlow(hasCallLogPermission())
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(RecentsFilter.ALL)

    val uiState: StateFlow<RecentsUiState> =
        combine(repository.callLog(), permissionState, query, filter) { entries, hasPermission, query, filter ->
            RecentsUiState(
                loading = false,
                entries = groupConsecutiveCalls(
                    entries.filter { matchesFilter(it, filter) && matchesCallQuery(it, query) },
                ),
                hasPermission = hasPermission,
                query = query,
                filter = filter,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecentsUiState(loading = true, hasPermission = hasCallLogPermission()),
        )

    fun refreshPermissionState() {
        permissionState.value = hasCallLogPermission()
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onFilterChange(value: RecentsFilter) {
        filter.value = value
    }

    fun onWhatsAppCall(entry: CallLogEntry) {
        whatsAppCallLauncher.startCall(
            number = entry.number.takeIf { it.isNotBlank() },
            name = entry.displayName,
        )
    }

    private fun hasCallLogPermission() = permissionChecker.has(Manifest.permission.READ_CALL_LOG)
}
