package org.jarsi.arkphone.ui.incall

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallInfo
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.util.formatDuration
import javax.inject.Inject

data class InCallUiState(
    val call: CallInfo? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val elapsed: String? = null,
    val showKeypad: Boolean = false,
    val callerPhotoUri: String? = null,
)

@Stable
interface InCallActions {
    fun onAnswer()
    fun onReject()
    fun onHangUp()
    fun onToggleMute()
    fun onToggleSpeaker()
    fun onToggleHold()
    fun onToggleKeypad()
    fun onDtmf(digit: Char)
}

@HiltViewModel
class InCallViewModel @Inject constructor(
    private val callController: CallController,
    private val contactsRepository: ContactsRepository,
    private val clock: Clock,
) : ViewModel(), InCallActions {

    private val keypadVisible = MutableStateFlow(false)

    private val ticker = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(1_000)
        }
    }

    private val callerPhotoUri = callController.calls
        .map { calls -> primaryCall(calls)?.number }
        .distinctUntilChanged()
        .map { number -> number?.let { contactsRepository.lookupContact(it)?.photoUri } }

    val uiState: StateFlow<InCallUiState> = combine(
        callController.calls, callController.audio, keypadVisible, ticker, callerPhotoUri,
    ) { calls, audio, keypad, now, photoUri ->
        val call = primaryCall(calls)
        InCallUiState(
            call = call,
            muted = audio.muted,
            speakerOn = audio.speakerOn,
            elapsed = call?.connectedAtMillis?.let { formatDuration((now - it) / 1_000) },
            showKeypad = keypad,
            callerPhotoUri = photoUri,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(1_000),
        initialValue = InCallUiState(),
    )

    private fun primaryCall(calls: List<CallInfo>): CallInfo? =
        calls.firstOrNull { it.status != CallStatus.DISCONNECTED } ?: calls.lastOrNull()

    private val primaryId: String?
        get() = uiState.value.call?.id

    override fun onAnswer() { primaryId?.let(callController::answer) }
    override fun onReject() { primaryId?.let(callController::reject) }
    override fun onHangUp() { primaryId?.let(callController::hangUp) }
    override fun onToggleMute() = callController.toggleMute()
    override fun onToggleSpeaker() = callController.toggleSpeaker()
    override fun onToggleHold() { primaryId?.let(callController::toggleHold) }
    override fun onToggleKeypad() { keypadVisible.value = !keypadVisible.value }
    override fun onDtmf(digit: Char) { primaryId?.let { callController.playDtmf(it, digit) } }
}
