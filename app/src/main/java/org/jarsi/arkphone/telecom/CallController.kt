package org.jarsi.arkphone.telecom

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val DTMF_TONE_MILLIS = 150L

@Singleton
class CallController @Inject constructor() {

    private val handles = LinkedHashMap<String, CallHandle>()

    private val dtmfExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dtmf-stop").apply { isDaemon = true }
    }

    internal var scheduleDtmfStop: (Runnable) -> Future<*> = { action ->
        dtmfExecutor.schedule(action, DTMF_TONE_MILLIS, TimeUnit.MILLISECONDS)
    }

    private var pendingDtmfStop: Future<*>? = null

    private val _calls = MutableStateFlow<List<CallInfo>>(emptyList())
    val calls: StateFlow<List<CallInfo>> = _calls.asStateFlow()

    /** Final snapshot of the last removed call when it ended in error. The
     *  calls list may already be empty by the time the UI collects it (state
     *  change and removal can conflate), but the cause must still be shown. */
    private val _endedCall = MutableStateFlow<CallInfo?>(null)
    val endedCall: StateFlow<CallInfo?> = _endedCall.asStateFlow()

    private val _audio = MutableStateFlow(CallAudioUiState())
    val audio: StateFlow<CallAudioUiState> = _audio.asStateFlow()

    /**
     * Fires when the last call disappears, but only if some call actually
     * went off hook — a ring that was missed or declined must not close the
     * app under the user.
     */
    private val _allCallsEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val allCallsEnded: SharedFlow<Unit> = _allCallsEnded.asSharedFlow()

    var audioController: InCallAudioController? = null

    @Synchronized
    fun onCallAdded(handle: CallHandle) {
        handles[handle.id] = handle
        _endedCall.value = null
        publish()
    }

    @Synchronized
    fun onCallChanged() = publish()

    /** The error explanation was shown and dismissed — nothing pends anymore. */
    @Synchronized
    fun clearEndedCall() {
        _endedCall.value = null
    }

    @Synchronized
    fun onCallRemoved(id: String) {
        val removed = handles.remove(id)
        if (removed?.disconnectError != null) {
            _endedCall.value = infoOf(removed).copy(status = CallStatus.DISCONNECTED)
        }
        pendingDtmfStop?.cancel(false)
        pendingDtmfStop = null
        publish()
        if (handles.isEmpty() && sawOffHookCall) {
            sawOffHookCall = false
            _allCallsEnded.tryEmit(Unit)
        }
    }

    @Synchronized
    fun answer(id: String) {
        handles[id]?.answer()
    }

    @Synchronized
    fun reject(id: String) {
        handles[id]?.reject()
    }

    @Synchronized
    fun hangUp(id: String) {
        handles[id]?.disconnect()
    }

    @Synchronized
    fun toggleHold(id: String) {
        val handle = handles[id] ?: return
        if (mapTelecomState(handle.telecomState) == CallStatus.HOLDING) handle.unhold() else handle.hold()
    }

    @Synchronized
    fun playDtmf(id: String, digit: Char) {
        val handle = handles[id] ?: return
        // A rapid second digit must not be cut short by the previous digit's
        // stop task: replace the pending stop and end the old tone now.
        pendingDtmfStop?.let { if (it.cancel(false)) handle.stopDtmf() }
        handle.playDtmf(digit)
        pendingDtmfStop = scheduleDtmfStop { handle.stopDtmf() }
    }

    fun toggleMute() {
        audioController?.applyMuted(!_audio.value.muted)
    }

    fun toggleSpeaker() {
        audioController?.applyRoute(speaker = !_audio.value.speakerOn)
    }

    fun onAudioStateChanged(muted: Boolean, speakerOn: Boolean, earpiece: Boolean = false) {
        _audio.value = CallAudioUiState(muted = muted, speakerOn = speakerOn, earpiece = earpiece)
    }

    private var sawOffHookCall = false

    private fun infoOf(handle: CallHandle) = CallInfo(
        id = handle.id,
        number = handle.number,
        displayName = handle.displayName,
        status = mapTelecomState(handle.telecomState),
        connectedAtMillis = handle.connectTimeMillis.takeIf { it > 0 },
        simAccountId = handle.simAccountId,
        disconnectError = handle.disconnectError,
    )

    private fun publish() {
        _calls.value = handles.values.map(::infoOf)
        if (_calls.value.any { it.status.isOffHook }) sawOffHookCall = true
    }
}
