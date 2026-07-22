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
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

data class RecentsUiState(
    val loading: Boolean = true,
    val entries: List<CallLogEntry> = emptyList(),
    val hasPermission: Boolean = true,
)

@HiltViewModel
class RecentsViewModel @Inject constructor(
    repository: CallLogRepository,
    private val permissionChecker: PermissionChecker,
) : ViewModel() {

    private val permissionState = MutableStateFlow(hasCallLogPermission())

    val uiState: StateFlow<RecentsUiState> =
        combine(repository.callLog(), permissionState) { entries, hasPermission ->
            RecentsUiState(loading = false, entries = entries, hasPermission = hasPermission)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = RecentsUiState(loading = true, hasPermission = hasCallLogPermission()),
        )

    fun refreshPermissionState() {
        permissionState.value = hasCallLogPermission()
    }

    private fun hasCallLogPermission() = permissionChecker.has(Manifest.permission.READ_CALL_LOG)
}
