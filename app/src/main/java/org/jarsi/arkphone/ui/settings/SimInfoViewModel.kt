package org.jarsi.arkphone.ui.settings

import android.Manifest
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.SimRepository
import org.jarsi.arkphone.data.model.SimCard
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

data class SimInfoUiState(
    val sims: List<SimCard> = emptyList(),
    val loading: Boolean = true,
    val canRequestNumberPermission: Boolean = false,
) {
    val showNumberPermissionButton: Boolean
        get() = canRequestNumberPermission && sims.isNotEmpty() && sims.all { it.number == null }
}

@HiltViewModel
class SimInfoViewModel @Inject constructor(
    private val simRepository: SimRepository,
    private val permissionChecker: PermissionChecker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SimInfoUiState())
    val uiState: StateFlow<SimInfoUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = SimInfoUiState(
                sims = simRepository.activeSims(),
                loading = false,
                canRequestNumberPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !permissionChecker.has(Manifest.permission.READ_PHONE_NUMBERS),
            )
        }
    }
}
