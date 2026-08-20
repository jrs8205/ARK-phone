package org.jarsi.arkphone.ui.contactcard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkAccount
import org.jarsi.arkphone.voip.ArkCode
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.ArkLinkRepository
import org.jarsi.arkphone.voip.ArkLookupResult
import org.jarsi.arkphone.voip.VoipAccountGateway
import org.jarsi.arkphone.voip.arkLinkKey
import java.util.Optional
import javax.inject.Inject

enum class ArkLinkError { INVALID_CODE, NOT_FOUND, LOOKUP_FAILED }

data class ContactCardUiState(
    val loading: Boolean = true,
    val details: ContactDetails? = null,
    val blocked: Boolean = false,
    val canBlock: Boolean = false,
    /** False in builds without the VoIP engine: the ARK rows never appear. */
    val arkAvailable: Boolean = false,
    val arkLink: ArkLink? = null,
    /** An account fetched for the code the user typed, awaiting confirmation. */
    val arkPending: ArkAccount? = null,
    val arkLookingUp: Boolean = false,
    val arkError: ArkLinkError? = null,
)

@HiltViewModel
class ContactCardViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    private val blockedNumbersRepository: BlockedNumbersRepository,
    private val arkLinkRepository: ArkLinkRepository,
    private val accountGateway: Optional<VoipAccountGateway>,
    private val clock: Clock,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactCardUiState())
    val uiState: StateFlow<ContactCardUiState> = _uiState.asStateFlow()

    fun load(contactId: Long) {
        viewModelScope.launch {
            val details = contactsRepository.contactDetails(contactId)
            val firstNumber = details?.phones?.firstOrNull()?.value
            _uiState.value = ContactCardUiState(
                loading = false,
                details = details,
                blocked = firstNumber != null && blockedNumbersRepository.isBlocked(firstNumber),
                canBlock = firstNumber != null && blockedNumbersRepository.canBlock(),
                arkAvailable = accountGateway.isPresent && firstNumber != null,
                arkLink = firstNumber?.let { linkFor(it) },
            )
        }
    }

    fun onToggleBlocked() {
        val state = _uiState.value
        val numbers = state.details?.phones?.map { it.value }.orEmpty()
        val firstNumber = numbers.firstOrNull() ?: return
        viewModelScope.launch {
            if (state.blocked) {
                numbers.forEach { blockedNumbersRepository.unblock(it) }
            } else {
                numbers.forEach { blockedNumbersRepository.block(it) }
            }
            // The provider can refuse a change (role lost, one number
            // failing); show its real state instead of the intent.
            _uiState.value = _uiState.value.copy(
                blocked = blockedNumbersRepository.isBlocked(firstNumber),
            )
        }
    }

    /** Validates locally first: a malformed code costs a round trip and a 404. */
    fun onArkCodeEntered(input: String) {
        val gateway = accountGateway.orElse(null) ?: return
        val code = ArkCode.canonicalize(input)
        if (code == null) {
            _uiState.value = _uiState.value.copy(
                arkError = ArkLinkError.INVALID_CODE,
                arkPending = null,
            )
            return
        }
        _uiState.value = _uiState.value.copy(arkLookingUp = true, arkError = null)
        viewModelScope.launch {
            val result = runCatching { gateway.lookUp(code) }
                .getOrDefault(ArkLookupResult.Failed)
            _uiState.value = _uiState.value.copy(
                arkLookingUp = false,
                arkPending = (result as? ArkLookupResult.Found)?.account,
                arkError = when (result) {
                    is ArkLookupResult.Found -> null
                    ArkLookupResult.NotFound -> ArkLinkError.NOT_FOUND
                    ArkLookupResult.Failed -> ArkLinkError.LOOKUP_FAILED
                },
            )
        }
    }

    fun onArkLinkConfirmed() {
        val account = _uiState.value.arkPending ?: return
        val number = _uiState.value.details?.phones?.firstOrNull()?.value ?: return
        viewModelScope.launch {
            arkLinkRepository.link(
                number = number,
                code = account.code,
                nickname = account.nickname,
                publicKey = account.publicKey,
                atMillis = clock.nowMillis(),
            )
            _uiState.value = _uiState.value.copy(
                arkPending = null,
                arkError = null,
                arkLink = linkFor(number),
            )
        }
    }

    fun onArkLinkDismissed() {
        _uiState.value = _uiState.value.copy(arkPending = null, arkError = null)
    }

    fun onArkUnlink() {
        val number = _uiState.value.details?.phones?.firstOrNull()?.value ?: return
        viewModelScope.launch {
            arkLinkRepository.unlink(number)
            _uiState.value = _uiState.value.copy(arkLink = null)
        }
    }

    private suspend fun linkFor(number: String): ArkLink? {
        val key = arkLinkKey(number)
        if (key.isEmpty()) return null
        return arkLinkRepository.links.first().firstOrNull { it.numberKey == key }
    }
}
