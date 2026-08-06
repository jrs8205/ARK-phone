package org.jarsi.arkphone.voip

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class StreamPeerConnectionAdapterFactory(
    private val provider: PeerConnectionFactoryProvider,
) : PeerConnectionAdapterFactory {
    override fun create(
        iceServers: List<IceServerConfig>,
        relayOnly: Boolean,
    ): PeerConnectionAdapter = StreamPeerConnectionAdapter(provider.factory, iceServers, relayOnly)
}

class StreamPeerConnectionAdapter(
    factory: PeerConnectionFactory,
    iceServers: List<IceServerConfig>,
    relayOnly: Boolean,
) : PeerConnectionAdapter {

    private val _events = MutableSharedFlow<AdapterEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<AdapterEvent> = _events

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            val json = buildJsonObject {
                put("sdpMid", candidate.sdpMid ?: "")
                put("sdpMLineIndex", candidate.sdpMLineIndex)
                put("sdp", candidate.sdp)
            }
            _events.tryEmit(AdapterEvent.LocalIceCandidate(json.toString()))
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED ->
                    _events.tryEmit(AdapterEvent.Connected)
                PeerConnection.PeerConnectionState.FAILED ->
                    _events.tryEmit(AdapterEvent.Failed)
                else -> Unit
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    private val connection: PeerConnection = factory.createPeerConnection(
        PeerConnection.RTCConfiguration(
            iceServers.map { server ->
                PeerConnection.IceServer.builder(server.urls)
                    .apply {
                        if (server.username != null && server.credential != null) {
                            setUsername(server.username)
                            setPassword(server.credential)
                        }
                    }
                    .createIceServer()
            },
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            if (relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
        },
        observer,
    ) ?: error("PeerConnection creation failed")

    init {
        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("audio0", audioSource)
        connection.addTrack(audioTrack, listOf("stream0"))
    }

    override suspend fun createOfferSdp(): String {
        val offer = suspendSdp { observer -> connection.createOffer(observer, MediaConstraints()) }
        suspendSet { observer -> connection.setLocalDescription(observer, offer) }
        return offer.description
    }

    override suspend fun createAnswerSdp(remoteOfferSdp: String): String {
        suspendSet { observer ->
            connection.setRemoteDescription(
                observer,
                SessionDescription(SessionDescription.Type.OFFER, remoteOfferSdp),
            )
        }
        val answer = suspendSdp { observer -> connection.createAnswer(observer, MediaConstraints()) }
        suspendSet { observer -> connection.setLocalDescription(observer, answer) }
        return answer.description
    }

    override suspend fun acceptAnswer(remoteAnswerSdp: String) {
        suspendSet { observer ->
            connection.setRemoteDescription(
                observer,
                SessionDescription(SessionDescription.Type.ANSWER, remoteAnswerSdp),
            )
        }
    }

    override fun addRemoteIceCandidate(candidateJson: String) {
        val parsed = try {
            Json.parseToJsonElement(candidateJson) as JsonObject
        } catch (_: Exception) {
            return
        }
        connection.addIceCandidate(
            IceCandidate(
                parsed["sdpMid"]?.jsonPrimitive?.content,
                parsed["sdpMLineIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                parsed["sdp"]?.jsonPrimitive?.content ?: "",
            ),
        )
    }

    override suspend fun stats(): StatsSnapshot? = suspendCancellableCoroutine { cont ->
        connection.getStats { report ->
            var usingRelay = false
            var rttMs: Int? = null
            var lossPercent: Int? = null
            var packetsLost = 0.0
            var packetsReceived = 0.0
            for (stats in report.statsMap.values) {
                when (stats.type) {
                    "candidate-pair" -> {
                        if (stats.members["state"] == "succeeded") {
                            (stats.members["currentRoundTripTime"] as? Double)?.let {
                                rttMs = (it * 1000).toInt()
                            }
                        }
                    }
                    "local-candidate" -> {
                        if (stats.members["candidateType"] == "relay") usingRelay = true
                    }
                    "inbound-rtp" -> {
                        packetsLost += (stats.members["packetsLost"] as? Number)?.toDouble() ?: 0.0
                        packetsReceived +=
                            (stats.members["packetsReceived"] as? Number)?.toDouble() ?: 0.0
                    }
                }
            }
            if (packetsReceived > 0) {
                lossPercent = ((packetsLost / (packetsLost + packetsReceived)) * 100).toInt()
            }
            cont.resume(StatsSnapshot(usingRelay, rttMs, lossPercent))
        }
    }

    override fun close() {
        connection.close()
    }

    private suspend fun suspendSdp(
        block: (SdpObserver) -> Unit,
    ): SessionDescription = suspendCancellableCoroutine { cont ->
        block(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) = cont.resume(sdp)
            override fun onCreateFailure(error: String?) =
                cont.resumeWithException(IllegalStateException(error ?: "createSdp failed"))
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        })
    }

    private suspend fun suspendSet(block: (SdpObserver) -> Unit): Unit =
        suspendCancellableCoroutine { cont ->
            block(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetSuccess() = cont.resume(Unit)
                override fun onSetFailure(error: String?) =
                    cont.resumeWithException(IllegalStateException(error ?: "setSdp failed"))
            })
        }
}
