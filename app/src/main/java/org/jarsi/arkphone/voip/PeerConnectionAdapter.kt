package org.jarsi.arkphone.voip

import kotlinx.coroutines.flow.SharedFlow

sealed interface AdapterEvent {
    data class LocalIceCandidate(val candidateJson: String) : AdapterEvent
    data object Connected : AdapterEvent
    data object Failed : AdapterEvent
}

data class StatsSnapshot(
    val usingRelay: Boolean,
    val rttMs: Int?,
    val packetLossPercent: Int?,
)

/** Boundary around libwebrtc so call choreography is unit-testable. */
interface PeerConnectionAdapter {
    val events: SharedFlow<AdapterEvent>
    suspend fun createOfferSdp(): String
    suspend fun createAnswerSdp(remoteOfferSdp: String): String
    suspend fun acceptAnswer(remoteAnswerSdp: String)

    /** The mute button: disables the local audio track without renegotiating. */
    fun setMicEnabled(enabled: Boolean)
    fun addRemoteIceCandidate(candidateJson: String)
    suspend fun stats(): StatsSnapshot?
    fun close()
}

fun interface PeerConnectionAdapterFactory {
    fun create(iceServers: List<IceServerConfig>, relayOnly: Boolean): PeerConnectionAdapter
}
