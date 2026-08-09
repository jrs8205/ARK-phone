package org.jarsi.arkphone.voip

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jarsi.arkphone.data.ArkIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
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
        val identity = ArkIdentity("ARK-AAAA-AAAA", "A", "t")
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(identity),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        var refreshed = false
        ArkVoipStartup(
            engine,
            { },
            { refreshed = true },
            MutableStateFlow<ArkIdentity?>(identity),
            backgroundScope,
        ).onAppStart()
        runCurrent()
        assertTrue(refreshed)
        assertEquals(1, connector.handles.size)
    }

    @Test
    fun theInboxOpensTheMomentRegistrationCompletes() = runTest {
        val connector = StartupConnector()
        val repository = TestArkIdentityRepository(null)
        val engine = VoipEngine(
            identityRepository = repository,
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        ArkVoipStartup(engine, { }, { }, repository.state, backgroundScope).onAppStart()
        runCurrent()
        assertEquals(0, connector.handles.size)
        repository.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "t")
        runCurrent()
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
        ArkVoipStartup(
            engine,
            { received += it },
            { },
            MutableStateFlow<ArkIdentity?>(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            backgroundScope,
        ).onAppStart()
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
        assertEquals("ARK-BBBB-BBBB", received.single().fromCode)
        assertEquals("v=0", received.single().offerSdp)
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
        val startup = ArkVoipStartup(
            engine,
            { },
            { },
            MutableStateFlow<ArkIdentity?>(ArkIdentity("ARK-AAAA-AAAA", "A", "t")),
            backgroundScope,
        )
        startup.onAppStart()
        startup.onAppStart()
        runCurrent()
        assertEquals(1, connector.handles.size)
    }
}
