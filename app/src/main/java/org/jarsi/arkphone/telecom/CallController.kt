package org.jarsi.arkphone.telecom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
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

    private val _calls = MutableStateFlow<List<CallInfo>>(emptyList())
    val calls: StateFlow<List<CallInfo>> = _calls.asStateFlow()

    private val _audio = MutableStateFlow(CallAudioUiState())
    val audio: StateFlow<CallAudioUiState> = _audio.asStateFlow()

    var audioController: InCallAudioController? = null

    @Synchronized
    fun onCallAdded(handle: CallHandle) {
        handles[handle.id] = handle
        publish()
    }

    @Synchronized
    fun onCallChanged() = publish()

    @Synchronized
    fun onCallRemoved(id: String) {
        handles.remove(id)
        publish()
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
        handle.playDtmf(digit)
        dtmfExecutor.schedule({ handle.stopDtmf() }, DTMF_TONE_MILLIS, TimeUnit.MILLISECONDS)
    }

    fun toggleMute() {
        audioController?.applyMuted(!_audio.value.muted)
    }

    fun toggleSpeaker() {
        audioController?.applyRoute(speaker = !_audio.value.speakerOn)
    }

    fun onAudioStateChanged(muted: Boolean, speakerOn: Boolean) {
        _audio.value = CallAudioUiState(muted = muted, speakerOn = speakerOn)
    }

    private fun publish() {
        _calls.value = handles.values.map { handle ->
            CallInfo(
                id = handle.id,
                number = handle.number,
                displayName = handle.displayName,
                status = mapTelecomState(handle.telecomState),
                connectedAtMillis = handle.connectTimeMillis.takeIf { it > 0 },
            )
        }
    }
}
