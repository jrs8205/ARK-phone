package org.jarsi.arkphone.ui.settings

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.telecom.FullScreenIntentPermission
import org.jarsi.arkphone.util.PermissionChecker
import org.jarsi.arkphone.voip.VoipAccountGateway
import java.util.Optional
import javax.inject.Inject

data class ArkCallsUiState(
    /** False in builds without the VoIP engine: the whole screen hides. */
    val available: Boolean = false,
    val enabled: Boolean = true,
    val code: String? = null,
    val nickname: String = "",
    val registering: Boolean = false,
    val registerFailed: Boolean = false,
    val micPermissionMissing: Boolean = false,
    val fullScreenIntentMissing: Boolean = false,
)

@HiltViewModel
class ArkCallsViewModel @Inject constructor(
    private val identityRepository: ArkIdentityRepository,
    private val settingsRepository: SettingsRepository,
    private val accountGateway: Optional<VoipAccountGateway>,
    private val permissionChecker: PermissionChecker,
    private val fullScreenIntentPermission: FullScreenIntentPermission,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArkCallsUiState(available = accountGateway.isPresent))
    val uiState: StateFlow<ArkCallsUiState> = _uiState.asStateFlow()

    init {
        refreshPermissions()
        viewModelScope.launch {
            identityRepository.identity.collect { identity ->
                _uiState.value = _uiState.value.copy(
                    code = identity?.code,
                    nickname = identity?.nickname ?: _uiState.value.nickname,
                )
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.value =
                    _uiState.value.copy(enabled = settings.arkInternetCallsEnabled)
            }
        }
    }

    /** Re-checked on every resume: both grants happen outside this screen. */
    fun refreshPermissions() {
        if (!_uiState.value.available) return
        _uiState.value = _uiState.value.copy(
            micPermissionMissing = !permissionChecker.has(Manifest.permission.RECORD_AUDIO),
            fullScreenIntentMissing = !fullScreenIntentPermission.allowed(),
        )
    }

    fun onNicknameChanged(nickname: String) {
        _uiState.value = _uiState.value.copy(nickname = nickname, registerFailed = false)
    }

    fun onEnabledChanged(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(enabled = enabled)
        viewModelScope.launch { settingsRepository.setArkInternetCallsEnabled(enabled) }
    }

    fun onRegister() {
        val gateway = accountGateway.orElse(null) ?: return
        val nickname = _uiState.value.nickname.trim()
        // The worker trims and then demands 1..40 characters; a blank nickname
        // would be a guaranteed 400.
        if (nickname.isEmpty() || nickname.length > MAX_NICKNAME_LENGTH) return
        if (_uiState.value.registering) return
        _uiState.value = _uiState.value.copy(registering = true, registerFailed = false)
        viewModelScope.launch {
            val registration = gateway.register(nickname)
            if (registration == null) {
                _uiState.value = _uiState.value.copy(registering = false, registerFailed = true)
                return@launch
            }
            // Persist before anything else: the device token is shown once and
            // is unrecoverable (worker/docs/protocol.md §12 rule 1).
            identityRepository.save(
                ArkIdentity(
                    code = registration.code,
                    nickname = nickname,
                    deviceToken = registration.deviceToken,
                ),
            )
            _uiState.value = _uiState.value.copy(
                registering = false,
                registerFailed = false,
                code = registration.code,
            )
        }
    }

    private companion object {
        const val MAX_NICKNAME_LENGTH = 40
    }
}
