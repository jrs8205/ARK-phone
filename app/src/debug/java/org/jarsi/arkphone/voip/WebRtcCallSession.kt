package org.jarsi.arkphone.voip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface VoipCallState {
    data object Idle : VoipCallState
    data object Connecting : VoipCallState
    data class Ringing(val offerSdp: String) : VoipCallState
    data object InCall : VoipCallState
    data class Ended(val reason: String) : VoipCallState
}

class WebRtcCallSession(
    private val signaling: SignalingClient,
    private val adapterFactory: PeerConnectionAdapterFactory,
    private val turnFetcher: suspend () -> List<IceServerConfig>?,
    private val scope: CoroutineScope,
    private val peerId: String,
) {
    private val _state = MutableStateFlow<VoipCallState>(VoipCallState.Idle)
    val state: StateFlow<VoipCallState> = _state.asStateFlow()

    var relayOnly: Boolean = false

    var activeAdapter: PeerConnectionAdapter? = null
        private set

    private var adapterJob: Job? = null

    init {
        scope.launch {
            signaling.incoming.collect { message -> onSignal(message) }
        }
    }

    fun placeCall() {
        if (_state.value != VoipCallState.Idle) return
        _state.value = VoipCallState.Connecting
        scope.launch {
            val adapter = openAdapter() ?: return@launch
            val offer = adapter.createOfferSdp()
            signaling.send(
                SignalingMessage(
                    type = SignalingTypes.CALL_OFFER,
                    to = peerId,
                    payload = buildJsonObject { put("sdp", offer) },
                ),
            )
        }
    }

    fun answer() {
        val ringing = _state.value as? VoipCallState.Ringing ?: return
        _state.value = VoipCallState.Connecting
        scope.launch {
            val adapter = openAdapter() ?: return@launch
            val answer = adapter.createAnswerSdp(ringing.offerSdp)
            signaling.send(
                SignalingMessage(
                    type = SignalingTypes.CALL_ANSWER,
                    to = peerId,
                    payload = buildJsonObject { put("sdp", answer) },
                ),
            )
        }
    }

    fun reject() {
        if (_state.value !is VoipCallState.Ringing) return
        signaling.send(SignalingMessage(type = SignalingTypes.CALL_REJECT, to = peerId))
        end("local-reject", notifyPeer = false)
    }

    fun hangUp() {
        if (_state.value == VoipCallState.Idle || _state.value is VoipCallState.Ended) return
        signaling.send(SignalingMessage(type = SignalingTypes.CALL_END, to = peerId))
        end("local-hangup", notifyPeer = false)
    }

    fun reset() {
        if (_state.value is VoipCallState.Ended) _state.value = VoipCallState.Idle
    }

    private suspend fun openAdapter(): PeerConnectionAdapter? {
        val iceServers = turnFetcher()
        if (iceServers == null) {
            end("no-turn", notifyPeer = false)
            return null
        }
        val adapter = adapterFactory.create(iceServers, relayOnly)
        activeAdapter = adapter
        adapterJob = scope.launch {
            adapter.events.collect { event ->
                when (event) {
                    is AdapterEvent.LocalIceCandidate -> signaling.send(
                        SignalingMessage(
                            type = SignalingTypes.ICE_CANDIDATE,
                            to = peerId,
                            payload = buildJsonObject { put("candidate", event.candidateJson) },
                        ),
                    )
                    AdapterEvent.Connected -> _state.value = VoipCallState.InCall
                    AdapterEvent.Failed -> end("connection-failed", notifyPeer = true)
                }
            }
        }
        return adapter
    }

    private fun onSignal(message: SignalingMessage) {
        when (message.type) {
            SignalingTypes.CALL_OFFER -> {
                if (_state.value != VoipCallState.Idle) return
                val sdp = message.payload?.get("sdp")?.jsonPrimitive?.content ?: return
                _state.value = VoipCallState.Ringing(sdp)
            }
            SignalingTypes.CALL_ANSWER -> {
                val sdp = message.payload?.get("sdp")?.jsonPrimitive?.content ?: return
                val adapter = activeAdapter ?: return
                scope.launch { adapter.acceptAnswer(sdp) }
            }
            SignalingTypes.ICE_CANDIDATE -> {
                val candidate =
                    message.payload?.get("candidate")?.jsonPrimitive?.content ?: return
                activeAdapter?.addRemoteIceCandidate(candidate)
            }
            SignalingTypes.CALL_REJECT ->
                if (_state.value != VoipCallState.Idle) end("rejected", notifyPeer = false)
            SignalingTypes.CALL_END ->
                if (_state.value != VoipCallState.Idle) end("peer-hangup", notifyPeer = false)
            SignalingTypes.ERROR -> {
                val code = message.payload?.get("code")?.jsonPrimitive?.content ?: "error"
                if (_state.value != VoipCallState.Idle) end(code, notifyPeer = false)
            }
        }
    }

    private fun end(reason: String, notifyPeer: Boolean) {
        if (notifyPeer) {
            signaling.send(SignalingMessage(type = SignalingTypes.CALL_END, to = peerId))
        }
        adapterJob?.cancel()
        activeAdapter?.close()
        activeAdapter = null
        _state.value = VoipCallState.Ended(reason)
    }
}
