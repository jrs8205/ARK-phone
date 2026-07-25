package org.jarsi.arkphone.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.telecom.CallScreeningRole
import org.jarsi.arkphone.util.NotificationAccessChecker
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val notificationAccessChecker: NotificationAccessChecker,
    private val callScreeningRole: CallScreeningRole,
) : ViewModel() {

    private val _hasScreeningRole = MutableStateFlow(callScreeningRole.isHeld())
    val hasScreeningRole: StateFlow<Boolean> = _hasScreeningRole.asStateFlow()

    fun refreshScreeningRole() {
        _hasScreeningRole.value = callScreeningRole.isHeld()
    }

    fun screeningRoleRequestIntent() = callScreeningRole.requestIntent()

    val uiState: StateFlow<Settings> = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = Settings(),
    )

    private val _hasNotificationAccess = MutableStateFlow(notificationAccessChecker.hasAccess())
    val hasNotificationAccess: StateFlow<Boolean> = _hasNotificationAccess.asStateFlow()

    fun refreshNotificationAccess() {
        _hasNotificationAccess.value = notificationAccessChecker.hasAccess()
    }

    fun onAnnounceModeChanged(mode: AnnounceMode) {
        viewModelScope.launch { settingsRepository.setAnnounceMode(mode) }
    }

    fun onAnnounceIntervalChanged(seconds: Int) {
        viewModelScope.launch { settingsRepository.setAnnounceIntervalSeconds(seconds) }
    }

    fun onAnnounceWhatsAppChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAnnounceWhatsApp(enabled) }
    }

    fun onBlockHiddenNumbersChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBlockHiddenNumbers(enabled) }
    }

    fun onBlockUnknownCallersChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBlockUnknownCallers(enabled) }
    }

    fun onAllowRepeatCallersChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAllowRepeatCallers(enabled) }
    }

    fun onAddBlockedPrefix(prefix: String) {
        val trimmed = prefix.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setBlockedPrefixes(uiState.value.blockedPrefixes + trimmed)
        }
    }

    fun onRemoveBlockedPrefix(prefix: String) {
        viewModelScope.launch {
            settingsRepository.setBlockedPrefixes(uiState.value.blockedPrefixes - prefix)
        }
    }

    fun onAlwaysAllowFavoritesChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAlwaysAllowFavorites(enabled) }
    }

    fun onAddAllowedNumber(number: String) {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.setAllowedNumbers(uiState.value.allowedNumbers + trimmed)
        }
    }

    fun onRemoveAllowedNumber(number: String) {
        viewModelScope.launch {
            settingsRepository.setAllowedNumbers(uiState.value.allowedNumbers - number)
        }
    }

    fun onBlockingScheduleEnabledChanged(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBlockingScheduleEnabled(enabled) }
    }

    fun onBlockingScheduleChanged(startMinutes: Int, endMinutes: Int) {
        viewModelScope.launch { settingsRepository.setBlockingSchedule(startMinutes, endMinutes) }
    }
}
