package org.jarsi.arkphone.voip

import app.cash.turbine.test
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoipEngineTest {

    private class StubHandle : WebSocketHandle {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close() = Unit
    }

    private class EngineConnector : WebSocketConnector {
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
        fun opens() = lastOnOpen!!()
        fun serverSends(message: SignalingMessage) = lastOnText!!(SignalingJson.encode(message))
    }

    private fun engine(
        connector: EngineConnector,
        scope: CoroutineScope,
        identity: ArkIdentity? = ArkIdentity("ARK-AAAA-AAAA", "A", "tok"),
    ) = VoipEngine(
        identityRepository = TestArkIdentityRepository(identity),
        connector = connector,
        config = VoipConfig("https://w"),
        scope = scope,
    )

    private fun offer(from: String, sdp: String) = SignalingMessage(
        type = SignalingTypes.CALL_OFFER,
        from = from,
        payload = buildJsonObject { put("sdp", sdp) },
    )

    @Test
    fun anUnregisteredDeviceNeverOpensASocket() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope, identity = null)
        assertFalse(engine.connect())
        assertTrue(connector.handles.isEmpty())
    }

    @Test
    fun connectOpensTheInboxOnceHoweverOftenItIsCalled() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope)
        val first = async { engine.connect() }
        runCurrent()
        connector.opens()
        assertTrue(first.await())
        assertTrue(engine.connect())
        assertEquals(1, connector.handles.size)
    }

    @Test
    fun theFlushIsDrainedBeforeAnythingRings() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope)
        engine.incomingCalls.test {
            val connecting = async { engine.connect() }
            runCurrent()
            connector.opens()
            connecting.await()
            connector.serverSends(offer("ARK-BBBB-BBBB", "v=0 b"))
            connector.serverSends(
                SignalingMessage(type = SignalingTypes.CALL_END, from = "ARK-BBBB-BBBB"),
            )
            connector.serverSends(offer("ARK-CCCC-CCCC", "v=0 c"))
            expectNoEvents()
            advanceTimeBy(FLUSH_DRAIN_MS + 100)
            runCurrent()
            assertEquals(IncomingArkCall("ARK-CCCC-CCCC", "v=0 c"), awaitItem())
        }
    }

    @Test
    fun anOfferArrivingAfterTheDrainRingsStraightAway() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope)
        val connecting = async { engine.connect() }
        runCurrent()
        connector.opens()
        connecting.await()
        advanceTimeBy(FLUSH_DRAIN_MS + 100)
        runCurrent()
        engine.incomingCalls.test {
            connector.serverSends(offer("ARK-BBBB-BBBB", "v=0 late"))
            assertEquals(IncomingArkCall("ARK-BBBB-BBBB", "v=0 late"), awaitItem())
        }
    }

    @Test
    fun reachReportsAPeerThatAnswersOnline() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope)
        val connecting = async { engine.connect() }
        runCurrent()
        connector.opens()
        connecting.await()
        advanceTimeBy(FLUSH_DRAIN_MS + 100)
        runCurrent()
        val reachable = async { engine.reach("ARK-BBBB-BBBB", 4_000) }
        runCurrent()
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject { put("online", true) },
            ),
        )
        assertTrue(reachable.await())
    }

    @Test
    fun reachOnAnUnregisteredDeviceIsFalseNotAnException() = runTest {
        val connector = EngineConnector()
        val engine = engine(connector, backgroundScope, identity = null)
        assertFalse(engine.reach("ARK-BBBB-BBBB", 4_000))
    }
}
