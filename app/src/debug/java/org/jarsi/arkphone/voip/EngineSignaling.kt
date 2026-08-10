package org.jarsi.arkphone.voip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull

/**
 * What one call needs from the signaling layer. The socket itself belongs to
 * the engine and outlives every call, so a call session only ever borrows it.
 */
interface CallSignaling {
    val incoming: Flow<SignalingMessage>
    fun send(message: SignalingMessage): Boolean
}

/**
 * Adapts the process-wide engine socket to one call's view of it. The engine
 * stream replays recent frames so a session created after its offer still
 * hears the gap; [sinceSeq] keeps an older call's replayed leftovers — a
 * stale call-end above all — out of this call.
 */
class EngineSignaling(
    private val engine: VoipEngine,
    private val sinceSeq: Long = 0L,
) : CallSignaling {
    override val incoming: Flow<SignalingMessage> =
        engine.signals.mapNotNull { stamped ->
            stamped.message.takeIf { stamped.seq > sinceSeq }
        }
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
     * [sinceSeq] is the call's replay horizon; pass a negative value for an
     * outgoing call to mean "from now on". [callId] is the offer's stamp for
     * an incoming call; null lets the session mint its own.
     */
    fun create(
        peerCode: String,
        offerSdp: String?,
        remoteCandidates: List<String>,
        sinceSeq: Long,
        callId: String?,
        scope: CoroutineScope,
    ): VoipMediaSession
}
