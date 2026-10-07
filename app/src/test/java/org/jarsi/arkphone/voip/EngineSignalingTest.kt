package org.jarsi.arkphone.voip

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jarsi.arkphone.data.ArkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineSignalingTest {

    private class StubHandle : WebSocketHandle {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() = Unit
    }

    private class StubConnector : WebSocketConnector {
        val handles = mutableListOf<StubHandle>()
        var lastOnOpen: (() -> Unit)? = null
        var lastOnText: ((String) -> Unit)? = null
        override fun connect(
            url: String,
            bearer: String,
            onOpen: () -> Unit,
            onText: (String) -> Unit,
            onClosed: (Int, String) -> Unit,
        ): WebSocketHandle {
            lastOnOpen = onOpen
            lastOnText = onText
            return StubHandle().also { handles += it }
        }
    }

    @Test
    fun signalsFromTheEngineReachTheCallSession() = runTest {
        val connector = StubConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val signaling = EngineSignaling(engine)
        val connecting = async { engine.connect() }
        runCurrent()
        connector.lastOnOpen!!()
        connecting.await()
        advanceTimeBy(FLUSH_DRAIN_MS + 100)
        runCurrent()
        signaling.incoming.test {
            connector.lastOnText!!(
                SignalingJson.encode(
                    SignalingMessage(
                        type = SignalingTypes.CALL_ANSWER,
                        from = "ARK-BBBB-BBBB",
                        payload = buildJsonObject { put("sdp", "v=0") },
                    ),
                ),
            )
            assertEquals(SignalingTypes.CALL_ANSWER, awaitItem().type)
        }
    }

    @Test
    fun sendsGoOutThroughTheEngineSocket() = runTest {
        val connector = StubConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val signaling = EngineSignaling(engine)
        assertFalse(signaling.send(SignalingMessage(type = SignalingTypes.CALL_END, to = "ARK-B")))
        val connecting = async { engine.connect() }
        runCurrent()
        connector.lastOnOpen!!()
        connecting.await()
        assertTrue(signaling.send(SignalingMessage(type = SignalingTypes.CALL_END, to = "ARK-B")))
        assertEquals(1, connector.handles.single().sent.size)
    }
}
