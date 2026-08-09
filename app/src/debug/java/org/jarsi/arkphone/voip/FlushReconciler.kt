package org.jarsi.arkphone.voip

import kotlinx.serialization.json.jsonPrimitive

/** One signaling frame with its emission order stamped on. */
data class StampedSignal(val seq: Long, val message: SignalingMessage)

/**
 * A call that should ring on this device. [sinceSeq] is the signal-stream
 * position of this call's offer: the session it creates replays only frames
 * newer than that, so it hears the gap between ring and subscribe without
 * ever replaying another call's leftovers.
 */
data class IncomingArkCall(
    val fromCode: String,
    val offerSdp: String,
    val iceCandidates: List<String> = emptyList(),
    val sinceSeq: Long = 0L,
)

/**
 * The inbox flushes every message queued for this account in the last 30 s,
 * oldest first, from every caller. The burst can therefore contain a complete
 * stale attempt, several callers, or an offer already followed by its end.
 * Reduce it to at most one call before ringing: the newest offer that has not
 * been cancelled by a later end or reject from the same peer, together with
 * the candidates that peer trickled after it — they were emitted before the
 * ringing session existed, so the seed is their only way in.
 */
fun reconcileFlush(messages: List<SignalingMessage>): IncomingArkCall? {
    val live = LinkedHashMap<String, String>()
    val candidates = HashMap<String, MutableList<String>>()
    for (message in messages) {
        // `from` is server-attested; a frame without one is not a real call.
        val from = message.from ?: continue
        when (message.type) {
            SignalingTypes.CALL_OFFER -> {
                val sdp = message.payload?.get("sdp")?.jsonPrimitive?.content ?: continue
                live.remove(from)
                live[from] = sdp
                candidates.remove(from)
            }
            SignalingTypes.CALL_END, SignalingTypes.CALL_REJECT -> {
                live.remove(from)
                candidates.remove(from)
            }
            SignalingTypes.ICE_CANDIDATE -> {
                val candidate =
                    message.payload?.get("candidate")?.jsonPrimitive?.content ?: continue
                if (live.containsKey(from)) {
                    candidates.getOrPut(from) { mutableListOf() }.add(candidate)
                }
            }
            else -> Unit
        }
    }
    val newest = live.entries.lastOrNull() ?: return null
    return IncomingArkCall(newest.key, newest.value, candidates[newest.key].orEmpty())
}
