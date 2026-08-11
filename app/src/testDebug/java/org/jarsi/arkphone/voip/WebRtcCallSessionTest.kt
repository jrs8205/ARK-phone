package org.jarsi.arkphone.voip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcCallSessionTest {

    private class FakeAdapter : PeerConnectionAdapter {
        override val events = MutableSharedFlow<AdapterEvent>(extraBufferCapacity = 8)
        var closed = false
        var remoteAnswer: String? = null
        var acceptAnswerGate: CompletableDeferred<Unit>? = null
        var throwOnAnswer = false
        val remoteCandidates = mutableListOf<String>()
        override suspend fun createOfferSdp() = "offer-sdp"
        override suspend fun createAnswerSdp(remoteOfferSdp: String): String {
            if (throwOnAnswer) throw IllegalStateException("malformed sdp")
            return "answer-sdp"
        }
        override suspend fun acceptAnswer(remoteAnswerSdp: String) {
            acceptAnswerGate?.await()
            remoteAnswer = remoteAnswerSdp
        }
        override fun addRemoteIceCandidate(candidateJson: String) { remoteCandidates.add(candidateJson) }
        override fun setMicEnabled(enabled: Boolean) { micOn = enabled }
        override suspend fun stats(): StatsSnapshot? = null
        override fun close() { closed = true }
        var micOn = true
    }

    private class FakeFactory : PeerConnectionAdapterFactory {
        val created = mutableListOf<FakeAdapter>()
        var lastRelayOnly = false
        var throwOnAnswer = false
        var throwOnCreate = false
        override fun create(
            iceServers: List<IceServerConfig>,
            relayOnly: Boolean,
        ): PeerConnectionAdapter {
            if (throwOnCreate) throw IllegalStateException("factory exhausted")
            lastRelayOnly = relayOnly
            return FakeAdapter().also {
                it.throwOnAnswer = throwOnAnswer
                created.add(it)
            }
        }
    }

    private class FakeSignaling : CallSignaling {
        val sent = mutableListOf<SignalingMessage>()
        private val _incoming = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 32)
        override val incoming: SharedFlow<SignalingMessage> = _incoming
        override fun send(message: SignalingMessage): Boolean {
            sent += message
            return true
        }
        fun serverSends(message: SignalingMessage) { _incoming.tryEmit(message) }
    }

    private class Harness(
        scope: kotlinx.coroutines.CoroutineScope,
        initialOfferSdp: String? = null,
        initialRemoteCandidates: List<String> = emptyList(),
        initialCallId: String? = null,
    ) {
        val signaling = FakeSignaling()
        val factory = FakeFactory()
        val session = WebRtcCallSession(
            signaling = signaling,
            adapterFactory = factory,
            turnFetcher = { listOf(IceServerConfig(urls = listOf("stun:s"))) },
            scope = scope,
            peerId = "phone-10pro",
            initialOfferSdp = initialOfferSdp,
            initialRemoteCandidates = initialRemoteCandidates,
            initialCallId = initialCallId,
        )

        fun serverSends(message: SignalingMessage) {
            signaling.serverSends(message)
        }

        fun sentOfType(type: String) = signaling.sent.filter { it.type == type }
    }

    @Test
    fun `notifyRinging sends a stamped call-ringing frame`() = runTest {
        val h = Harness(backgroundScope, initialOfferSdp = "their-offer", initialCallId = "call-b")
        runCurrent()
        h.session.notifyRinging()
        runCurrent()
        val ringing = h.sentOfType(SignalingTypes.CALL_RINGING).single()
        assertEquals("call-b", ringing.payload!!["callId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a stamped call-ringing marks the peer as ringing`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        val callId = h.sentOfType(SignalingTypes.CALL_OFFER).single()
            .payload!!["callId"]!!.jsonPrimitive.content
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_RINGING,
                from = "phone-10pro",
                payload = buildJsonObject { put("callId", callId) },
            ),
        )
        runCurrent()
        assertTrue(h.session.peerRinging.value)
    }

    @Test
    fun `a call-ringing stamped for another call is ignored`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_RINGING,
                from = "phone-10pro",
                payload = buildJsonObject { put("callId", "someone-elses-call") },
            ),
        )
        runCurrent()
        assertFalse(h.session.peerRinging.value)
    }

    @Test
    fun `the peer's answer stops the ringback signal`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        val callId = h.sentOfType(SignalingTypes.CALL_OFFER).single()
            .payload!!["callId"]!!.jsonPrimitive.content
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_RINGING,
                from = "phone-10pro",
                payload = buildJsonObject { put("callId", callId) },
            ),
        )
        runCurrent()
        assertTrue(h.session.peerRinging.value)
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_ANSWER,
                from = "phone-10pro",
                payload = buildJsonObject {
                    put("callId", callId)
                    put("sdp", "answer-sdp")
                },
            ),
        )
        runCurrent()
        assertFalse(h.session.peerRinging.value)
    }

    @Test
    fun `ending the call stops the ringback signal`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        val callId = h.sentOfType(SignalingTypes.CALL_OFFER).single()
            .payload!!["callId"]!!.jsonPrimitive.content
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_RINGING,
                from = "phone-10pro",
                payload = buildJsonObject { put("callId", callId) },
            ),
        )
        runCurrent()
        h.session.hangUp()
        runCurrent()
        assertFalse(h.session.peerRinging.value)
    }

    @Test
    fun `a call-end stamped for an earlier call does not end this one`() = runTest {
        val h = Harness(backgroundScope, initialOfferSdp = "their-offer", initialCallId = "call-b")
        runCurrent()
        // Call A's end, stashed in the resend queue and flushed after this
        // call began, must not kill call B as a peer hang-up.
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_END,
                from = "phone-10pro",
                payload = buildJsonObject { put("callId", "call-a") },
            ),
        )
        runCurrent()
        assertTrue(h.session.state.value is VoipCallState.Ringing)
    }

    @Test
    fun `an unstamped call-end from an older build still ends the call`() = runTest {
        val h = Harness(backgroundScope, initialOfferSdp = "their-offer", initialCallId = "call-b")
        runCurrent()
        h.serverSends(SignalingMessage(type = SignalingTypes.CALL_END, from = "phone-10pro"))
        runCurrent()
        assertEquals(VoipCallState.Ended("peer-hangup"), h.session.state.value)
    }

    @Test
    fun `outgoing frames carry the session's call id`() = runTest {
        val h = Harness(backgroundScope, initialOfferSdp = "their-offer", initialCallId = "call-b")
        runCurrent()
        h.session.answer()
        runCurrent()
        val answer = h.sentOfType(SignalingTypes.CALL_ANSWER).single()
        assertEquals("call-b", answer.payload!!["callId"]!!.jsonPrimitive.content)
        h.session.hangUp()
        runCurrent()
        val end = h.sentOfType(SignalingTypes.CALL_END).single()
        assertEquals("call-b", end.payload!!["callId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `answering a live offer echoes its call id`() = runTest {
        val h = Harness(backgroundScope)
        runCurrent()
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_OFFER,
                from = "phone-10pro",
                payload = buildJsonObject {
                    put("sdp", "their-offer")
                    put("callId", "call-x")
                },
            ),
        )
        runCurrent()
        h.session.answer()
        runCurrent()
        val answer = h.sentOfType(SignalingTypes.CALL_ANSWER).single()
        assertEquals("call-x", answer.payload!!["callId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `outgoing call sends offer then reaches InCall on answer and Connected`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        assertEquals(VoipCallState.Connecting, h.session.state.value)
        val offer = h.sentOfType(SignalingTypes.CALL_OFFER).single()
        assertEquals("offer-sdp", offer.payload!!["sdp"]!!.jsonPrimitive.content)
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_ANSWER,
                from = "phone-10pro",
                payload = buildJsonObject { put("sdp", "answer-sdp") },
            ),
        )
        runCurrent()
        assertEquals("answer-sdp", h.factory.created.single().remoteAnswer)
        h.factory.created.single().events.tryEmit(AdapterEvent.Connected)
        runCurrent()
        assertEquals(VoipCallState.InCall, h.session.state.value)
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `incoming offer rings and answering sends the answer sdp`() = runTest {
        val h = Harness(backgroundScope)
        runCurrent() // let the session's incoming-message collector subscribe
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_OFFER,
                from = "phone-10pro",
                payload = buildJsonObject { put("sdp", "their-offer") },
            ),
        )
        runCurrent()
        assertEquals(VoipCallState.Ringing("their-offer"), h.session.state.value)
        h.session.answer()
        runCurrent()
        val answer = h.sentOfType(SignalingTypes.CALL_ANSWER).single()
        assertEquals("answer-sdp", answer.payload!!["sdp"]!!.jsonPrimitive.content)
        h.factory.created.single().events.tryEmit(AdapterEvent.Connected)
        runCurrent()
        assertEquals(VoipCallState.InCall, h.session.state.value)
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `ice candidates flow both ways once the answer is accepted`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        h.factory.created.single().events.tryEmit(AdapterEvent.LocalIceCandidate("cand-1"))
        runCurrent()
        assertEquals(1, h.sentOfType(SignalingTypes.ICE_CANDIDATE).size)
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_ANSWER,
                from = "phone-10pro",
                payload = buildJsonObject { put("sdp", "answer-sdp") },
            ),
        )
        runCurrent()
        h.serverSends(remoteCandidate("cand-2"))
        runCurrent()
        assertEquals(listOf("cand-2"), h.factory.created.single().remoteCandidates)
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `candidates arriving while ringing are applied after answering`() = runTest {
        val h = Harness(backgroundScope)
        runCurrent()
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_OFFER,
                from = "phone-10pro",
                payload = buildJsonObject { put("sdp", "their-offer") },
            ),
        )
        h.serverSends(remoteCandidate("early-1"))
        h.serverSends(remoteCandidate("early-2"))
        runCurrent()
        assertTrue(h.factory.created.isEmpty())
        h.session.answer()
        runCurrent()
        assertEquals(listOf("early-1", "early-2"), h.factory.created.single().remoteCandidates)
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `candidates arriving before the answer is accepted wait for acceptance`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        val adapter = h.factory.created.single()
        val gate = CompletableDeferred<Unit>()
        adapter.acceptAnswerGate = gate
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_ANSWER,
                from = "phone-10pro",
                payload = buildJsonObject { put("sdp", "answer-sdp") },
            ),
        )
        h.serverSends(remoteCandidate("early"))
        runCurrent()
        assertTrue(adapter.remoteCandidates.isEmpty())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("early"), adapter.remoteCandidates)
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `rejecting a ringing call discards buffered candidates`() = runTest {
        val h = Harness(backgroundScope)
        runCurrent()
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_OFFER,
                from = "phone-10pro",
                payload = buildJsonObject { put("sdp", "their-offer") },
            ),
        )
        h.serverSends(remoteCandidate("stale"))
        runCurrent()
        h.session.reject()
        h.session.reset()
        runCurrent()
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.CALL_OFFER,
                from = "phone-10pro",
                payload = buildJsonObject { put("sdp", "second-offer") },
            ),
        )
        runCurrent()
        h.session.answer()
        runCurrent()
        assertTrue(h.factory.created.single().remoteCandidates.isEmpty())
        h.session.hangUp()
        runCurrent()
    }

    private fun remoteCandidate(candidate: String) = SignalingMessage(
        type = SignalingTypes.ICE_CANDIDATE,
        from = "phone-10pro",
        payload = buildJsonObject { put("candidate", candidate) },
    )

    @Test
    fun `peer-offline error ends the call attempt`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.ERROR,
                payload = buildJsonObject { put("code", "peer-offline") },
            ),
        )
        runCurrent()
        assertEquals(VoipCallState.Ended("peer-offline"), h.session.state.value)
        assertTrue(h.factory.created.single().closed)
    }

    @Test
    fun `remote call-end tears down and hangUp notifies the peer`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        h.serverSends(SignalingMessage(type = SignalingTypes.CALL_END, from = "phone-10pro"))
        runCurrent()
        assertEquals(VoipCallState.Ended("peer-hangup"), h.session.state.value)
        h.session.reset()
        assertEquals(VoipCallState.Idle, h.session.state.value)

        h.session.placeCall()
        runCurrent()
        h.session.hangUp()
        runCurrent()
        assertEquals(1, h.sentOfType(SignalingTypes.CALL_END).size)
        assertEquals(VoipCallState.Ended("local-hangup"), h.session.state.value)
    }

    @Test
    fun `hanging up during the reach pre-check ends the attempt without signaling`() = runTest {
        val h = Harness(backgroundScope)
        runCurrent()
        assertEquals(VoipCallState.Idle, h.session.state.value)
        h.session.hangUp()
        assertEquals(VoipCallState.Ended("local-hangup"), h.session.state.value)
        // Nothing was on the wire yet, so no peer may learn a call was attempted.
        assertTrue(h.signaling.sent.isEmpty())
    }

    @Test
    fun `frames from a third account never touch the call`() = runTest {
        val h = Harness(backgroundScope)
        h.session.placeCall()
        runCurrent()
        h.serverSends(SignalingMessage(type = SignalingTypes.CALL_END, from = "ARK-INTRUDER"))
        h.serverSends(
            SignalingMessage(
                type = SignalingTypes.ICE_CANDIDATE,
                from = "ARK-INTRUDER",
                payload = buildJsonObject { put("candidate", "evil") },
            ),
        )
        runCurrent()
        assertEquals(VoipCallState.Connecting, h.session.state.value)
        assertTrue(h.factory.created.single().remoteCandidates.isEmpty())
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `a throwing answer ends the call instead of crashing`() = runTest {
        val h = Harness(backgroundScope, initialOfferSdp = "their-offer")
        h.factory.throwOnAnswer = true
        runCurrent()
        h.session.answer()
        runCurrent()
        assertEquals(VoipCallState.Ended("media-error"), h.session.state.value)
        // The peer already knows about the call, so it must hear the end.
        assertEquals(1, h.sentOfType(SignalingTypes.CALL_END).size)
    }

    @Test
    fun `seeded flush candidates are applied when the call is answered`() = runTest {
        val h = Harness(
            backgroundScope,
            initialOfferSdp = "their-offer",
            initialRemoteCandidates = listOf("flush-1", "flush-2"),
        )
        runCurrent()
        assertEquals(VoipCallState.Ringing("their-offer"), h.session.state.value)
        h.session.answer()
        runCurrent()
        assertEquals(listOf("flush-1", "flush-2"), h.factory.created.single().remoteCandidates)
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `a throwing adapter factory ends the call instead of crashing`() = runTest {
        val h = Harness(backgroundScope)
        h.factory.throwOnCreate = true
        h.session.placeCall()
        runCurrent()
        assertEquals(VoipCallState.Ended("media-error"), h.session.state.value)
    }

    @Test
    fun `muting before the adapter exists lands on the created track`() = runTest {
        val h = Harness(backgroundScope)
        h.session.setMicEnabled(false)
        h.session.placeCall()
        runCurrent()
        assertEquals(false, h.factory.created.single().micOn)
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `relayOnly flag reaches the adapter factory`() = runTest {
        val h = Harness(backgroundScope)
        h.session.relayOnly = true
        h.session.placeCall()
        runCurrent()
        assertTrue(h.factory.lastRelayOnly)
        h.session.hangUp()
        runCurrent()
    }

    @Test
    fun `failed turn fetch ends with no-turn`() = runTest {
        val session =
            WebRtcCallSession(FakeSignaling(), FakeFactory(), { null }, backgroundScope, "phone-10pro")
        session.placeCall()
        runCurrent()
        assertEquals(VoipCallState.Ended("no-turn"), session.state.value)
    }
}
