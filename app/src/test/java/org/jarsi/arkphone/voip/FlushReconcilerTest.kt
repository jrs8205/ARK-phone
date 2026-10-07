package org.jarsi.arkphone.voip

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlushReconcilerTest {

    private fun offer(from: String, sdp: String, callId: String? = null) = SignalingMessage(
        type = SignalingTypes.CALL_OFFER,
        from = from,
        payload = buildJsonObject {
            put("sdp", sdp)
            callId?.let { put("callId", it) }
        },
    )

    private fun end(from: String, callId: String? = null) = SignalingMessage(
        type = SignalingTypes.CALL_END,
        from = from,
        payload = callId?.let { id -> buildJsonObject { put("callId", id) } },
    )

    private fun reject(from: String) =
        SignalingMessage(type = SignalingTypes.CALL_REJECT, from = from)

    @Test
    fun anEmptyFlushRingsForNobody() {
        assertNull(reconcileFlush(emptyList()))
    }

    @Test
    fun theOffersCallIdSurvivesReconciliation() {
        val stamped = SignalingMessage(
            type = SignalingTypes.CALL_OFFER,
            from = "ARK-BBBB-BBBB",
            payload = buildJsonObject {
                put("sdp", "v=0")
                put("callId", "call-x")
            },
        )
        assertEquals("call-x", reconcileFlush(listOf(stamped))!!.callId)
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

    private fun candidate(from: String, value: String, callId: String? = null) = SignalingMessage(
        type = SignalingTypes.ICE_CANDIDATE,
        from = from,
        payload = buildJsonObject {
            put("candidate", value)
            callId?.let { put("callId", it) }
        },
    )

    @Test
    fun candidatesTrickledAfterTheOfferRideAlongWithTheRing() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0"),
            candidate("ARK-BBBB-BBBB", "c-1"),
            candidate("ARK-BBBB-BBBB", "c-2"),
        )
        assertEquals(
            IncomingArkCall("ARK-BBBB-BBBB", "v=0", listOf("c-1", "c-2")),
            reconcileFlush(flush),
        )
    }

    @Test
    fun candidatesFromOtherPeersAndDeadAttemptsAreNotSeeded() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 stale"),
            candidate("ARK-BBBB-BBBB", "stale-c"),
            end("ARK-BBBB-BBBB"),
            candidate("ARK-CCCC-CCCC", "orphan-c"),
            offer("ARK-BBBB-BBBB", "v=0 live"),
            candidate("ARK-BBBB-BBBB", "live-c"),
        )
        assertEquals(
            IncomingArkCall("ARK-BBBB-BBBB", "v=0 live", listOf("live-c")),
            reconcileFlush(flush),
        )
    }

    @Test
    fun aStampedCandidateFromAnotherAttemptIsNotSeeded() {
        // The caller's resend queue can flush an earlier attempt's late
        // candidates after the live offer; their stamps prove whose they
        // are. An unstamped candidate (an older build) still rides along.
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 live", callId = "call-b"),
            candidate("ARK-BBBB-BBBB", "stale-c", callId = "call-a"),
            candidate("ARK-BBBB-BBBB", "live-c", callId = "call-b"),
            candidate("ARK-BBBB-BBBB", "unstamped-c"),
        )
        assertEquals(
            listOf("live-c", "unstamped-c"),
            reconcileFlush(flush)!!.iceCandidates,
        )
    }

    @Test
    fun aStaleStampedEndDoesNotCancelANewerStampedOffer() {
        // The caller's resend queue can flush an earlier attempt's end after
        // the live offer; both are stamped, so the mismatch proves the end
        // belongs to the dead attempt.
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0 live", callId = "call-b"),
            end("ARK-BBBB-BBBB", callId = "call-a"),
        )
        val ringing = reconcileFlush(flush)!!
        assertEquals("v=0 live", ringing.offerSdp)
        assertEquals("call-b", ringing.callId)
    }

    @Test
    fun aMatchingStampedEndStillCancelsItsOffer() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0", callId = "call-b"),
            end("ARK-BBBB-BBBB", callId = "call-b"),
        )
        assertNull(reconcileFlush(flush))
    }

    @Test
    fun anUnstampedEndFromAnOlderBuildStillCancelsAStampedOffer() {
        val flush = listOf(
            offer("ARK-BBBB-BBBB", "v=0", callId = "call-b"),
            end("ARK-BBBB-BBBB"),
        )
        assertNull(reconcileFlush(flush))
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
