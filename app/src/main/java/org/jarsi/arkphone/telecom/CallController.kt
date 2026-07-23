package org.jarsi.arkphone.telecom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallController @Inject constructor() {

    private val handles = LinkedHashMap<String, CallHandle>()

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

    fun answer(id: String) = handles[id]?.answer() ?: Unit
    fun reject(id: String) = handles[id]?.reject() ?: Unit
    fun hangUp(id: String) = handles[id]?.disconnect() ?: Unit

    fun toggleHold(id: String) {
        val handle = handles[id] ?: return
        if (mapTelecomState(handle.telecomState) == CallStatus.HOLDING) handle.unhold() else handle.hold()
    }

    fun playDtmf(id: String, digit: Char) {
        val handle = handles[id] ?: return
        handle.playDtmf(digit)
        handle.stopDtmf()
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
