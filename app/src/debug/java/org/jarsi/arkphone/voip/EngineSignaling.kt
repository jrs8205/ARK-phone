package org.jarsi.arkphone.voip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What one call needs from the signaling layer. The socket itself belongs to
 * the engine and outlives every call, so a call session only ever borrows it.
 */
interface CallSignaling {
    val incoming: SharedFlow<SignalingMessage>
    fun send(message: SignalingMessage): Boolean
}

/** Adapts the process-wide engine socket to one call's view of it. */
class EngineSignaling(private val engine: VoipEngine) : CallSignaling {
    override val incoming: SharedFlow<SignalingMessage> get() = engine.signals
    override fun send(message: SignalingMessage): Boolean = engine.send(message)
}

/** The media half of a call, as the coordinator drives it. */
interface VoipMediaSession {
    val state: StateFlow<VoipCallState>
    fun placeCall()
    fun answer()
    fun reject()
    fun hangUp()
    fun setMicEnabled(enabled: Boolean)
}

fun interface VoipMediaSessionFactory {
    /**
     * [offerSdp] is null for an outgoing call. [remoteCandidates] carries the
     * candidates that arrived in the same inbox flush as the offer — they were
     * emitted before any session existed, so this is their only way in.
     */
    fun create(
        peerCode: String,
        offerSdp: String?,
        remoteCandidates: List<String>,
        scope: CoroutineScope,
    ): VoipMediaSession
}
