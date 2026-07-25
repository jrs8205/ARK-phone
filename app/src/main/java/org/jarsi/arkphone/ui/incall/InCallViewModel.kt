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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallInfo
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.telecom.RejectMessageSender
import org.jarsi.arkphone.telecom.RingSilencer
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
    val knownCaller: Boolean = true,
    val lastCalledMillis: Long? = null,
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
    fun onSilence()
    fun onRejectWithMessage(message: String)
}

@HiltViewModel
class InCallViewModel @Inject constructor(
    private val callController: CallController,
    private val contactsRepository: ContactsRepository,
    private val callLogRepository: CallLogRepository,
    private val clock: Clock,
    private val ringSilencer: RingSilencer,
    private val rejectMessageSender: RejectMessageSender,
) : ViewModel(), InCallActions {

    private val keypadVisible = MutableStateFlow(false)

    private val ticker = flow {
        while (true) {
            emit(clock.nowMillis())
            delay(1_000)
        }
    }

    private data class CallerContext(
        val photoUri: String? = null,
        val known: Boolean = true,
        val lastCalledMillis: Long? = null,
    )

    private val callerContext = callController.calls
        .map { calls -> primaryCall(calls)?.number }
        .distinctUntilChanged()
        .map { number ->
            if (number == null) return@map CallerContext()
            val match = contactsRepository.lookupContact(number)
            val lastCalled = runCatching {
                callLogRepository.callLog().first()
                    .firstOrNull { sameCaller(it.number, number) }
                    ?.timestampMillis
            }.getOrNull()
            CallerContext(
                photoUri = match?.photoUri,
                known = match != null,
                lastCalledMillis = lastCalled,
            )
        }

    val uiState: StateFlow<InCallUiState> = combine(
        callController.calls, callController.audio, keypadVisible, ticker, callerContext,
    ) { calls, audio, keypad, now, caller ->
        val call = primaryCall(calls)
        InCallUiState(
            call = call,
            muted = audio.muted,
            speakerOn = audio.speakerOn,
            elapsed = call?.connectedAtMillis?.let { formatDuration((now - it) / 1_000) },
            showKeypad = keypad,
            callerPhotoUri = caller.photoUri,
            knownCaller = caller.known,
            lastCalledMillis = caller.lastCalledMillis,
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
    override fun onSilence() {
        uiState.value.call?.let(ringSilencer::silenceRinging)
    }

    override fun onRejectWithMessage(message: String) {
        val call = uiState.value.call ?: return
        call.number?.let { rejectMessageSender.send(it, message) }
        callController.reject(call.id)
    }
}

/** Loose same-caller check without the platform matcher: exact digits, or a
 *  shared 9-digit tail so national and international forms match. */
internal fun sameCaller(a: String, b: String): Boolean {
    val digitsA = a.filter { it.isDigit() }
    val digitsB = b.filter { it.isDigit() }
    if (digitsA.isEmpty() || digitsB.isEmpty()) return false
    if (digitsA == digitsB) return true
    return digitsA.length >= 7 && digitsB.length >= 7 &&
        digitsA.takeLast(9) == digitsB.takeLast(9)
}
