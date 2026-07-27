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
import kotlinx.coroutines.launch
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.SimAccountRepository
import org.jarsi.arkphone.data.model.SimAccount
import org.jarsi.arkphone.data.simLabelFor
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallInfo
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.telecom.isOffHook
import org.jarsi.arkphone.telecom.RejectMessageSender
import org.jarsi.arkphone.telecom.RingSilencer
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.util.Toaster
import org.jarsi.arkphone.util.formatDuration
import org.jarsi.arkphone.util.sameCaller
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
    val callerContactId: Long? = null,
    val callerDisplayName: String? = null,
    /** SIM the call uses; null when the device has only one. */
    val simLabel: String? = null,
    /** True once any call of this screen actually went off hook. */
    val sawOffHook: Boolean = false,
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
    private val simAccountRepository: SimAccountRepository,
    private val clock: Clock,
    private val ringSilencer: RingSilencer,
    private val rejectMessageSender: RejectMessageSender,
    private val toaster: Toaster,
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
        val contactId: Long? = null,
        val displayName: String? = null,
        val simLabel: String? = null,
    )

    private val simAccounts = MutableStateFlow<List<SimAccount>>(emptyList())

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
                contactId = match?.contactId,
                displayName = match?.displayName,
            )
        }

    // Folded into the caller context so the outer combine stays within its
    // five-flow limit.
    private val callerContextWithSim = combine(
        callerContext,
        callController.calls,
        simAccounts,
    ) { context, calls, sims ->
        context.copy(simLabel = simLabelFor(primaryCall(calls)?.simAccountId, sims))
    }

    init {
        viewModelScope.launch { simAccounts.value = simAccountRepository.accounts() }
    }

    // Telecom removes the call before the screen's closing grace ends; keep
    // the last known caller so the name doesn't flash to "unknown" at hangup.
    private var lastShownCall: CallInfo? = null

    // Sticky: the closing screen must still know whether this was a real
    // conversation (close the whole app) or just a ring that went unanswered.
    private var offHookSeen = false

    val uiState: StateFlow<InCallUiState> = combine(
        callController.calls, callController.audio, keypadVisible, ticker, callerContextWithSim,
    ) { calls, audio, keypad, now, caller ->
        val live = primaryCall(calls)
        if (live != null) lastShownCall = live
        if (calls.any { it.status.isOffHook }) offHookSeen = true
        val call = live ?: lastShownCall?.copy(status = CallStatus.DISCONNECTED)
        InCallUiState(
            call = call,
            muted = audio.muted,
            speakerOn = audio.speakerOn,
            elapsed = call?.connectedAtMillis?.let { formatDuration((now - it) / 1_000) },
            showKeypad = keypad,
            callerPhotoUri = caller.photoUri,
            knownCaller = caller.known,
            lastCalledMillis = caller.lastCalledMillis,
            callerContactId = caller.contactId,
            callerDisplayName = caller.displayName,
            simLabel = caller.simLabel,
            sawOffHook = offHookSeen,
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
        // The rejection happens regardless — the user asked for it — but a
        // failed send (missing SEND_SMS, radio error) must not stay silent.
        val sent = call.number?.let { rejectMessageSender.send(it, message) } == true
        if (!sent) toaster.show(R.string.incall_reject_message_failed)
        callController.reject(call.id)
    }
}
