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
    private val signaling: CallSignaling,
    private val adapterFactory: PeerConnectionAdapterFactory,
    private val turnFetcher: suspend () -> List<IceServerConfig>?,
    private val scope: CoroutineScope,
    private val peerId: String,
    initialOfferSdp: String? = null,
    initialRemoteCandidates: List<String> = emptyList(),
    initialCallId: String? = null,
) : VoipMediaSession {
    private val _state = MutableStateFlow<VoipCallState>(VoipCallState.Idle)
    override val state: StateFlow<VoipCallState> = _state.asStateFlow()

    private val _peerRinging = MutableStateFlow(false)
    override val peerRinging: StateFlow<Boolean> = _peerRinging.asStateFlow()

    override fun notifyRinging() {
        if (_state.value !is VoipCallState.Ringing) return
        signaling.send(frame(SignalingTypes.CALL_RINGING))
    }

    // The one call this session is. The caller mints it, the callee adopts it
    // from the offer; a frame stamped for any other call — a stale end resent
    // out of a dead socket's queue — must not touch this one. Null from an
    // older build's frame is accepted unchecked.
    private var callId: String = initialCallId ?: java.util.UUID.randomUUID().toString()

    var relayOnly: Boolean = false

    var activeAdapter: PeerConnectionAdapter? = null
        private set

    private var adapterJob: Job? = null

    // libwebrtc rejects addIceCandidate until the remote description is set, so
    // candidates arriving before that (receiver: before Answer is pressed) are
    // held here and flushed once the description lands.
    private val pendingRemoteCandidates = mutableListOf<String>()
    private var remoteDescriptionSet = false

    init {
        if (initialOfferSdp != null) _state.value = VoipCallState.Ringing(initialOfferSdp)
        // Candidates that arrived in the same inbox flush as the offer were
        // emitted before this session existed; without this seed they are gone.
        pendingRemoteCandidates += initialRemoteCandidates
        scope.launch {
            signaling.incoming.collect { message -> onSignal(message) }
        }
    }

    override fun placeCall() {
        if (_state.value != VoipCallState.Idle) return
        _state.value = VoipCallState.Connecting
        scope.launch {
            val adapter = openAdapter(notifyPeer = false) ?: return@launch
            val offer = media { adapter.createOfferSdp() }
            if (offer == null) {
                // The peer knows nothing yet; die quietly and let the
                // coordinator route the call over the carrier instead.
                end("media-error", notifyPeer = false)
                return@launch
            }
            signaling.send(frame(SignalingTypes.CALL_OFFER) { put("sdp", offer) })
        }
    }

    /** Every frame this session emits is stamped with its call id. */
    private fun frame(
        type: String,
        build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
    ) = SignalingMessage(
        type = type,
        to = peerId,
        payload = buildJsonObject {
            put("callId", callId)
            build()
        },
    )

    override fun answer() {
        val ringing = _state.value as? VoipCallState.Ringing ?: return
        _state.value = VoipCallState.Connecting
        scope.launch {
            val adapter = openAdapter(notifyPeer = true) ?: return@launch
            val answer = media { adapter.createAnswerSdp(ringing.offerSdp) }
            if (answer == null) {
                // A malformed remote offer must end the call, not the process.
                end("media-error", notifyPeer = true)
                return@launch
            }
            if (activeAdapter !== adapter) return@launch
            remoteDescriptionSet = true
            flushPendingCandidates(adapter)
            signaling.send(frame(SignalingTypes.CALL_ANSWER) { put("sdp", answer) })
        }
    }

    /** libwebrtc surfaces bad SDP as exceptions; only cancellation may pass. */
    private suspend fun <T> media(block: suspend () -> T): T? = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override fun reject() {
        if (_state.value !is VoipCallState.Ringing) return
        signaling.send(frame(SignalingTypes.CALL_REJECT))
        end("local-reject", notifyPeer = false)
    }

    override fun hangUp() {
        when (_state.value) {
            is VoipCallState.Ended -> return
            // The outgoing pre-check phase: nothing is on the wire yet, so no
            // peer may learn of the attempt, but the attempt itself must die —
            // otherwise the coordinator falls back to a carrier call the user
            // just cancelled.
            VoipCallState.Idle -> end("local-hangup", notifyPeer = false)
            else -> {
                signaling.send(frame(SignalingTypes.CALL_END))
                end("local-hangup", notifyPeer = false)
            }
        }
    }

    // Remembered so a mute pressed during reach or TURN setup — before any
    // adapter exists — still lands on the track the moment it is created.
    private var micEnabled = true

    override fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        activeAdapter?.setMicEnabled(enabled)
    }

    fun reset() {
        if (_state.value is VoipCallState.Ended) _state.value = VoipCallState.Idle
    }

    private suspend fun openAdapter(notifyPeer: Boolean): PeerConnectionAdapter? {
        val iceServers = turnFetcher()
        if (iceServers == null) {
            end("no-turn", notifyPeer = notifyPeer)
            return null
        }
        // Factory failure (native init, resource exhaustion) is a media
        // failure like any other — it must end the call, not the process.
        val adapter = media { adapterFactory.create(iceServers, relayOnly) }
        if (adapter == null) {
            end("media-error", notifyPeer = notifyPeer)
            return null
        }
        adapter.setMicEnabled(micEnabled)
        activeAdapter = adapter
        adapterJob = scope.launch {
            adapter.events.collect { event ->
                when (event) {
                    is AdapterEvent.LocalIceCandidate -> signaling.send(
                        frame(SignalingTypes.ICE_CANDIDATE) {
                            put("candidate", event.candidateJson)
                        },
                    )
                    AdapterEvent.Connected -> {
                        _peerRinging.value = false
                        _state.value = VoipCallState.InCall
                    }
                    AdapterEvent.Failed -> end("connection-failed", notifyPeer = true)
                }
            }
        }
        return adapter
    }

    private fun onSignal(message: SignalingMessage) {
        // The engine socket carries every peer's frames. Server errors have no
        // from; anything else must come from THE peer of this call — any other
        // registered account is a bystander and must not get to end the call
        // or feed it candidates.
        if (message.type != SignalingTypes.ERROR && message.from != peerId) return
        // An offer STARTS a call rather than belonging to this one, and an
        // unstamped frame is an older build; everything else must be stamped
        // for THIS call — a stale end resent out of a dead socket's queue
        // used to kill the peer's next call as a phantom hang-up.
        val frameCallId = message.payload?.get("callId")?.jsonPrimitive?.content
        if (message.type != SignalingTypes.CALL_OFFER &&
            message.type != SignalingTypes.ERROR &&
            frameCallId != null && frameCallId != callId
        ) {
            return
        }
        when (message.type) {
            SignalingTypes.CALL_OFFER -> {
                if (_state.value != VoipCallState.Idle) return
                val sdp = message.payload?.get("sdp")?.jsonPrimitive?.content ?: return
                frameCallId?.let { callId = it }
                _state.value = VoipCallState.Ringing(sdp)
            }
            SignalingTypes.CALL_RINGING ->
                if (_state.value == VoipCallState.Connecting) _peerRinging.value = true
            SignalingTypes.CALL_ANSWER -> {
                // The peer picked up: ringback must die now, not when the
                // media finally connects.
                _peerRinging.value = false
                val sdp = message.payload?.get("sdp")?.jsonPrimitive?.content ?: return
                val adapter = activeAdapter ?: return
                scope.launch {
                    if (media { adapter.acceptAnswer(sdp) } == null) {
                        end("media-error", notifyPeer = true)
                        return@launch
                    }
                    if (activeAdapter !== adapter) return@launch
                    remoteDescriptionSet = true
                    flushPendingCandidates(adapter)
                }
            }
            SignalingTypes.ICE_CANDIDATE -> {
                val candidate =
                    message.payload?.get("candidate")?.jsonPrimitive?.content ?: return
                when {
                    _state.value == VoipCallState.Idle ||
                        _state.value is VoipCallState.Ended -> Unit
                    remoteDescriptionSet -> activeAdapter?.addRemoteIceCandidate(candidate)
                    else -> pendingRemoteCandidates += candidate
                }
            }
            SignalingTypes.CALL_REJECT ->
                if (_state.value != VoipCallState.Idle) end("rejected", notifyPeer = false)
            SignalingTypes.CALL_END ->
                if (_state.value != VoipCallState.Idle) end("peer-hangup", notifyPeer = false)
            SignalingTypes.ERROR -> {
                val code = message.payload?.get("code")?.jsonPrimitive?.content ?: "error"
                // Version skew: an older worker answers a frame type it does
                // not know (call-ringing) with unknown-type — the frame was
                // advisory and the call itself is fine.
                if (code == "unknown-type") return
                if (_state.value != VoipCallState.Idle) end(code, notifyPeer = false)
            }
        }
    }

    private fun end(reason: String, notifyPeer: Boolean) {
        if (notifyPeer) {
            signaling.send(frame(SignalingTypes.CALL_END))
        }
        adapterJob?.cancel()
        activeAdapter?.close()
        activeAdapter = null
        pendingRemoteCandidates.clear()
        remoteDescriptionSet = false
        _peerRinging.value = false
        _state.value = VoipCallState.Ended(reason)
    }

    private fun flushPendingCandidates(adapter: PeerConnectionAdapter) {
        pendingRemoteCandidates.forEach(adapter::addRemoteIceCandidate)
        pendingRemoteCandidates.clear()
    }
}
