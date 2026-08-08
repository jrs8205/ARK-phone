package org.jarsi.arkphone.voip

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlushReconcilerTest {

    private fun offer(from: String, sdp: String) = SignalingMessage(
        type = SignalingTypes.CALL_OFFER,
        from = from,
        payload = buildJsonObject { put("sdp", sdp) },
    )

    private fun end(from: String) =
        SignalingMessage(type = SignalingTypes.CALL_END, from = from)

    private fun reject(from: String) =
        SignalingMessage(type = SignalingTypes.CALL_REJECT, from = from)

    @Test
    fun anEmptyFlushRingsForNobody() {
        assertNull(reconcileFlush(emptyList()))
    }

    @Test
    fun aLoneOfferRings() {
        assertEquals(
            IncomingArkCall("ARK-BBBB-BBBB", "v=0"),
            reconcileFlush(listOf(offer("ARK-BBBB-BBBB", "v=0"))),
        )
    }

    @Test
    fun anOfferAlreadyFollowedByItsEndNeverRings() {
        assertNull(reconcileFlush(listOf(offer("ARK-BBBB-BBBB", "v=0"), end("ARK-BBBB-BBBB"))))
    }

    @Test
    fun aCancelledAttemptDoesNotCancelALaterOneFromTheSamePeer() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 first"),
            end("ARK-BBBB-BBBB"),
            offer("ARK-BBBB-BBBB", "v=0 second"),
        )
        assertEquals(IncomingArkCall("ARK-BBBB-BBBB", "v=0 second"), reconcileFlush(flush))
    }

    @Test
    fun withTwoCallersTheNewestSurvivingOfferWins() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 b"),
            offer("ARK-CCCC-CCCC", "v=0 c"),
        )
        assertEquals(IncomingArkCall("ARK-CCCC-CCCC", "v=0 c"), reconcileFlush(flush))
    }

    @Test
    fun aCancelledNewestOfferFallsBackToTheSurvivingOlderOne() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 b"),
            offer("ARK-CCCC-CCCC", "v=0 c"),
            reject("ARK-CCCC-CCCC"),
        )
        assertEquals(IncomingArkCall("ARK-BBBB-BBBB", "v=0 b"), reconcileFlush(flush))
    }

    @Test
    fun framesWithoutAServerAttestedFromAreIgnored() {
        val flush = listOf(
            SignalingMessage(
                type = SignalingTypes.CALL_OFFER,
                payload = buildJsonObject { put("sdp", "v=0") },
            ),
            SignalingMessage(type = SignalingTypes.ICE_CANDIDATE, from = "ARK-BBBB-BBBB"),
        )
        assertNull(reconcileFlush(flush))
    }
}
