package org.jarsi.arkphone.voip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WebRtcCallSessionTest {

    private class FakeAdapter : PeerConnectionAdapter {
        override val events = MutableSharedFlow<AdapterEvent>(extraBufferCapacity = 8)
        var closed = false
        var remoteAnswer: String? = null
        var acceptAnswerGate: CompletableDeferred<Unit>? = null
        val remoteCandidates = mutableListOf<String>()
        override suspend fun createOfferSdp() = "offer-sdp"
        override suspend fun createAnswerSdp(remoteOfferSdp: String) = "answer-sdp"
        override suspend fun acceptAnswer(remoteAnswerSdp: String) {
            acceptAnswerGate?.await()
            remoteAnswer = remoteAnswerSdp
        }
        override fun addRemoteIceCandidate(candidateJson: String) { remoteCandidates.add(candidateJson) }
        override suspend fun stats(): StatsSnapshot? = null
        override fun close() { closed = true }
    }

    private class FakeFactory : PeerConnectionAdapterFactory {
        val created = mutableListOf<FakeAdapter>()
        var lastRelayOnly = false
        override fun create(
            iceServers: List<IceServerConfig>,
            relayOnly: Boolean,
        ): PeerConnectionAdapter {
            lastRelayOnly = relayOnly
            return FakeAdapter().also { created.add(it) }
        }
    }

    private class RecordingConnector : WebSocketConnector {
        var onText: ((String) -> Unit)? = null
        val sent = mutableListOf<String>()
        override fun connect(
            url: String,
            onText: (String) -> Unit,
            onClosed: () -> Unit,
        ): WebSocketHandle {
            this.onText = onText
            return object : WebSocketHandle {
                override fun send(text: String): Boolean { sent.add(text); return true }
                override fun close() {}
            }
        }
    }

    private class Harness(scope: kotlinx.coroutines.CoroutineScope) {
        val connector = RecordingConnector()
        val signaling = SignalingClient(connector, "https://w", "phone-8a", "phone-10pro", scope)
        val factory = FakeFactory()
        val session = WebRtcCallSession(
            signaling = signaling,
            adapterFactory = factory,
            turnFetcher = { listOf(IceServerConfig(urls = listOf("stun:s"))) },
            scope = scope,
            peerId = "phone-10pro",
        )

        init { signaling.start() }

        fun serverSends(message: SignalingMessage) {
            connector.onText!!(SignalingJson.encode(message))
        }

        fun sentOfType(type: String) =
            connector.sent.mapNotNull { SignalingJson.decode(it) }.filter { it.type == type }
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
        val connector = object : WebSocketConnector {
            override fun connect(
                url: String,
                onText: (String) -> Unit,
                onClosed: () -> Unit,
            ) = object : WebSocketHandle {
                override fun send(text: String) = true
                override fun close() {}
            }
        }
        val signaling =
            SignalingClient(connector, "https://w", "phone-8a", "phone-10pro", backgroundScope)
        signaling.start()
        val session =
            WebRtcCallSession(signaling, FakeFactory(), { null }, backgroundScope, "phone-10pro")
        session.placeCall()
        runCurrent()
        assertEquals(VoipCallState.Ended("no-turn"), session.state.value)
        signaling.stop()
    }
}
