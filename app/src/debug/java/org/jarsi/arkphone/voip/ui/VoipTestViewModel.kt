package org.jarsi.arkphone.voip.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jarsi.arkphone.voip.SignalingClient
import org.jarsi.arkphone.voip.SignalingConnectionState
import org.jarsi.arkphone.voip.StatsSnapshot
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.WebRtcCallSession
import javax.inject.Inject

interface VoipSessionFactory {
    fun create(deviceId: String, peerId: String, scope: CoroutineScope): VoipSessionHandles
}

data class VoipSessionHandles(
    val signaling: SignalingClient,
    val session: WebRtcCallSession,
)

data class VoipUiState(
    val deviceId: String? = null,
    val peerId: String? = null,
    val connectionState: SignalingConnectionState = SignalingConnectionState.DISCONNECTED,
    val peerOnline: Boolean = false,
    val callState: VoipCallState = VoipCallState.Idle,
    val relayOnly: Boolean = false,
    val stats: StatsSnapshot? = null,
    val speakerOn: Boolean = false,
)

@HiltViewModel
class VoipTestViewModel @Inject constructor(
    private val sessionFactory: VoipSessionFactory,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VoipUiState())
    val uiState: StateFlow<VoipUiState> = _uiState.asStateFlow()

    private var handles: VoipSessionHandles? = null
    private var statsJob: Job? = null

    /** Set by the activity so the ViewModel can drive the speaker and service. */
    var audioController: VoipAudioController? = null

    fun pickDevice(id: String) {
        if (handles != null) return
        val peer = if (id == "phone-8a") "phone-10pro" else "phone-8a"
        val created = sessionFactory.create(id, peer, viewModelScope)
        handles = created
        _uiState.value = _uiState.value.copy(deviceId = id, peerId = peer)
        created.signaling.start()
        viewModelScope.launch {
            created.signaling.connectionState.collect {
                _uiState.value = _uiState.value.copy(connectionState = it)
            }
        }
        viewModelScope.launch {
            created.signaling.peerOnline.collect {
                _uiState.value = _uiState.value.copy(peerOnline = it)
            }
        }
        viewModelScope.launch {
            created.session.state.collect { callState ->
                _uiState.value = _uiState.value.copy(callState = callState)
                audioController?.onCallStateChanged(callState)
                // Poll stats only during a call, so tests without a call never
                // schedule the timer loop.
                if (callState == VoipCallState.InCall) {
                    if (statsJob == null) {
                        statsJob = viewModelScope.launch {
                            while (true) {
                                delay(2_000)
                                val stats = handles?.session?.activeAdapter?.stats()
                                _uiState.value = _uiState.value.copy(stats = stats)
                            }
                        }
                    }
                } else {
                    statsJob?.cancel()
                    statsJob = null
                }
            }
        }
    }

    fun placeCall() { handles?.session?.placeCall() }
    fun answer() { handles?.session?.answer() }
    fun reject() { handles?.session?.reject() }
    fun hangUp() { handles?.session?.hangUp() }
    fun dismissEnded() { handles?.session?.reset() }

    fun setRelayOnly(enabled: Boolean) {
        handles?.session?.relayOnly = enabled
        _uiState.value = _uiState.value.copy(relayOnly = enabled)
    }

    fun toggleSpeaker() {
        val next = !_uiState.value.speakerOn
        _uiState.value = _uiState.value.copy(speakerOn = next)
        audioController?.setSpeaker(next)
    }

    override fun onCleared() {
        handles?.session?.hangUp()
        handles?.signaling?.stop()
        super.onCleared()
    }
}

/** Implemented by the activity: speakerphone + foreground service control. */
interface VoipAudioController {
    fun onCallStateChanged(state: VoipCallState)
    fun setSpeaker(on: Boolean)
}
