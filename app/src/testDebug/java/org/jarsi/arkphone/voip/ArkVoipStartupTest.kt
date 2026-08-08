package org.jarsi.arkphone.voip

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jarsi.arkphone.data.ArkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArkVoipStartupTest {

    private class StubHandle : WebSocketHandle {
        override fun send(text: String): Boolean = true
        override fun close() = Unit
    }

    private class StartupConnector : WebSocketConnector {
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
    fun startupRefreshesTheFcmTokenAndOpensTheInbox() = runTest {
        val connector = StartupConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        var refreshed = false
        ArkVoipStartup(engine, { }, { refreshed = true }, backgroundScope).onAppStart()
        runCurrent()
        assertTrue(refreshed)
        assertEquals(1, connector.handles.size)
    }

    @Test
    fun aReconciledIncomingCallReachesTheCoordinator() = runTest {
        val connector = StartupConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val received = mutableListOf<IncomingArkCall>()
        ArkVoipStartup(engine, { received += it }, { }, backgroundScope).onAppStart()
        runCurrent()
        connector.lastOnOpen!!()
        runCurrent()
        connector.lastOnText!!(
            SignalingJson.encode(
                SignalingMessage(
                    type = SignalingTypes.CALL_OFFER,
                    from = "ARK-BBBB-BBBB",
                    payload = buildJsonObject { put("sdp", "v=0") },
                ),
            ),
        )
        advanceTimeBy(FLUSH_DRAIN_MS + 100)
        runCurrent()
        assertEquals(listOf(IncomingArkCall("ARK-BBBB-BBBB", "v=0")), received)
    }

    @Test
    fun startupIsIdempotent() = runTest {
        val connector = StartupConnector()
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val startup = ArkVoipStartup(engine, { }, { }, backgroundScope)
        startup.onAppStart()
        startup.onAppStart()
        runCurrent()
        assertEquals(1, connector.handles.size)
    }
}
