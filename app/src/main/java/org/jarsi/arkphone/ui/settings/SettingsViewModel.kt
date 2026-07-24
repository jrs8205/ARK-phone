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
import org.jarsi.arkphone.util.NotificationAccessChecker
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val notificationAccessChecker: NotificationAccessChecker,
) : ViewModel() {

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
}
