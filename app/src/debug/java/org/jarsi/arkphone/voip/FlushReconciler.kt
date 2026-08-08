package org.jarsi.arkphone.voip

import kotlinx.serialization.json.jsonPrimitive

/** A call that should ring on this device. */
data class IncomingArkCall(val fromCode: String, val offerSdp: String)

/**
 * The inbox flushes every message queued for this account in the last 30 s,
 * oldest first, from every caller. The burst can therefore contain a complete
 * stale attempt, several callers, or an offer already followed by its end.
 * Reduce it to at most one call before ringing: the newest offer that has not
 * been cancelled by a later end or reject from the same peer.
 */
fun reconcileFlush(messages: List<SignalingMessage>): IncomingArkCall? {
    val live = LinkedHashMap<String, String>()
    for (message in messages) {
        // `from` is server-attested; a frame without one is not a real call.
        val from = message.from ?: continue
        when (message.type) {
            SignalingTypes.CALL_OFFER -> {
                val sdp = message.payload?.get("sdp")?.jsonPrimitive?.content ?: continue
                live.remove(from)
                live[from] = sdp
            }
            SignalingTypes.CALL_END, SignalingTypes.CALL_REJECT -> live.remove(from)
            else -> Unit
        }
    }
    val newest = live.entries.lastOrNull() ?: return null
    return IncomingArkCall(newest.key, newest.value)
}
