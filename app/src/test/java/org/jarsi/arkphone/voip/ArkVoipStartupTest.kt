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
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallHandle
import org.jarsi.arkphone.telecom.ProximityController
import org.jarsi.arkphone.telecom.ProximityLock
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

    private class FakeProximityLock : ProximityLock {
        var held = false
        override fun acquire() {
            held = true
        }
        override fun release() {
            held = false
        }
    }

    /** The startup demands one; tests not about proximity get a throwaway. */
    private fun kotlinx.coroutines.test.TestScope.proximity() =
        ProximityController(CallController(), FakeProximityLock(), backgroundScope)

    private class StartupCallHandle : CallHandle {
        override val id: String = "voip-out-ARK-BBBB-BBBB"
        override var telecomState: Int = android.telecom.Call.STATE_ACTIVE
        override val number: String? = "0401234567"
        override val displayName: String? = null
        override val connectTimeMillis: Long = 0
        override val simAccountId: String? = null
        override fun answer() = Unit
        override fun reject() = Unit
        override fun disconnect() = Unit
        override fun hold() = Unit
        override fun unhold() = Unit
        override fun playDtmf(digit: Char) = Unit
        override fun stopDtmf() = Unit
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
            proximity(),
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
        ArkVoipStartup(engine, { }, { }, repository.state, backgroundScope, proximity())
            .onAppStart()
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
            proximity(),
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
            proximity(),
        )
        startup.onAppStart()
        startup.onAppStart()
        runCurrent()
        assertEquals(1, connector.handles.size)
    }

    @Test
    fun startupArmsTheProximityScreenOffForArkCalls() = runTest {
        val connector = StartupConnector()
        val identity = ArkIdentity("ARK-AAAA-AAAA", "A", "t")
        val engine = VoipEngine(
            identityRepository = TestArkIdentityRepository(identity),
            connector = connector,
            config = VoipConfig("https://w"),
            scope = backgroundScope,
        )
        val callController = CallController()
        val lock = FakeProximityLock()
        // The startup must own the controller: the self-managed path binds no
        // InCallService, so nothing else ever instantiates it — an ARK call
        // at the ear left the screen lit (field-hit 2026-08-10 morning).
        ArkVoipStartup(
            engine,
            { },
            { },
            MutableStateFlow<ArkIdentity?>(identity),
            backgroundScope,
            ProximityController(callController, lock, backgroundScope),
        ).onAppStart()
        runCurrent()
        callController.onInCallUiVisibility(true)
        callController.onAudioStateChanged(muted = false, speakerOn = false, earpiece = true)
        callController.onCallAdded(StartupCallHandle())
        runCurrent()
        assertTrue(lock.held)
    }
}
