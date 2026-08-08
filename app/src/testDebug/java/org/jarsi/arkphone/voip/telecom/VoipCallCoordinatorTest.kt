package org.jarsi.arkphone.voip.telecom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.IncomingArkCall
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.VoipMediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoipCallCoordinatorTest {

    private class FakeSession : VoipMediaSession {
        val calls = mutableListOf<String>()
        private val _state = MutableStateFlow<VoipCallState>(VoipCallState.Idle)
        override val state: StateFlow<VoipCallState> = _state
        override fun placeCall() { calls += "placeCall" }
        override fun answer() { calls += "answer" }
        override fun reject() { calls += "reject" }
        override fun hangUp() { calls += "hangUp" }
        fun moveTo(next: VoipCallState) { _state.value = next }
    }

    private class FakeTelecom(var accepts: Boolean = true) : VoipTelecom {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var lastAnswer: (() -> Unit)? = null
        var lastDisconnect: (() -> Unit)? = null
        override fun add(
            handle: VoipCallHandle,
            onSystemAnswer: () -> Unit,
            onSystemDisconnect: () -> Unit,
        ): Boolean {
            if (!accepts) return false
            added += handle.id
            lastAnswer = onSystemAnswer
            lastDisconnect = onSystemDisconnect
            return true
        }
        override fun setActive(id: String) = Unit
        override fun remove(id: String) { removed += id }
    }

    private class FakeUi : VoipCallUi {
        val events = mutableListOf<String>()
        override fun added(handle: VoipCallHandle) { events += "added" }
        override fun changed() { events += "changed" }
        override fun removed(id: String) { events += "removed" }
        override fun showIncoming(handle: VoipCallHandle) { events += "showIncoming" }
        override fun showOngoing(handle: VoipCallHandle) { events += "showOngoing" }
        override fun clearNotification() { events += "clearNotification" }
        override fun openCallScreen() { events += "openCallScreen" }
        override fun startCallService() { events += "startCallService" }
        override fun stopCallService() { events += "stopCallService" }
    }

    private class FakeCallLog : ArkCallLog {
        val records = mutableListOf<ArkCallRecord>()
        override fun record(record: ArkCallRecord) { records += record }
    }

    private class FakeReach(var reachable: Boolean) {
        val queries = mutableListOf<String>()
        suspend fun reach(code: String, timeoutMs: Long): Boolean {
            queries += code
            return reachable
        }
    }

    private val session = FakeSession()
    private val telecom = FakeTelecom()
    private val ui = FakeUi()
    private val callLog = FakeCallLog()
    private val missed = mutableListOf<ArkCallRecord>()

    private val link = ArkLink(
        numberKey = "445552841",
        number = "+358 44 5552841",
        code = "ARK-BBBB-BBBB",
        nickname = "Jarsi",
        publicKey = "pk",
        linkedAtMillis = 1_000L,
    )

    private fun coordinator(
        scope: CoroutineScope,
        reach: FakeReach,
        nicknameFor: (String) -> String? = { "Jarsi" },
        numberFor: (String) -> String? = { "+358 44 5552841" },
    ) = VoipCallCoordinator(
        reachCheck = { code, timeout -> reach.reach(code, timeout) },
        sessionFactory = { _, _, _ -> session },
        telecom = telecom,
        ui = ui,
        nicknameForCode = nicknameFor,
        numberForCode = numberFor,
        callLog = callLog,
        missedCalls = { missed += it },
        clock = Clock { 1_000L },
        scope = scope,
    )

    @Test
    fun aRefusingPlatformMeansTheCallerMustUseTheCarrier() = runTest {
        telecom.accepts = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        assertFalse(coordinator.startCall(link) { })
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun anUnreachablePeerFallsBackToTheCarrierWithoutRinging() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = false))
        assertTrue(coordinator.startCall(link) { fellBack = true })
        runCurrent()
        assertTrue(fellBack)
        assertTrue(session.calls.isEmpty())
        assertEquals(listOf("voip-out-ARK-BBBB-BBBB"), telecom.removed)
    }

    @Test
    fun aReachablePeerGetsAnOfferAndTheCallScreenOpens() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        assertTrue(coordinator.startCall(link) { })
        runCurrent()
        assertEquals(listOf("placeCall"), session.calls)
        assertTrue(ui.events.contains("added"))
        assertTrue(ui.events.contains("openCallScreen"))
    }

    @Test
    fun aCallThatNeverConnectsFallsBackAtFifteenSeconds() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        assertFalse(fellBack)
        advanceTimeBy(VOIP_CONNECT_TIMEOUT_MS + 100)
        runCurrent()
        assertTrue(fellBack)
        assertTrue(session.calls.contains("hangUp"))
        assertTrue(ui.events.contains("removed"))
    }

    @Test
    fun aConnectedCallIsNotTornDownByTheTimeout() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        session.moveTo(VoipCallState.InCall)
        advanceTimeBy(VOIP_CONNECT_TIMEOUT_MS + 100)
        runCurrent()
        assertFalse(fellBack)
        assertTrue(ui.events.contains("startCallService"))
    }

    @Test
    fun aDeclinedCallDoesNotFallBackToTheCarrier() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        session.moveTo(VoipCallState.Ended("rejected"))
        runCurrent()
        assertFalse(fellBack)
        assertTrue(ui.events.contains("removed"))
    }

    @Test
    fun anIncomingCallRingsThroughTheExistingNotification() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        assertTrue(ui.events.contains("showIncoming"))
        assertTrue(ui.events.contains("added"))
        assertEquals(listOf("voip-in-ARK-BBBB-BBBB"), telecom.added)
    }

    @Test
    fun aSecondCallWhileOneIsUpIsRefused() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { }
        runCurrent()
        assertFalse(coordinator.startCall(link) { })
    }

    @Test
    fun anAnsweredCallIsWrittenToTheCallLog() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { }
        runCurrent()
        session.moveTo(VoipCallState.InCall)
        runCurrent()
        session.moveTo(VoipCallState.Ended("peer-hangup"))
        runCurrent()
        assertEquals(ArkCallType.OUTGOING, callLog.records.single().type)
        assertTrue(missed.isEmpty())
    }

    @Test
    fun anUnansweredIncomingCallLeavesAMissedCall() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        session.moveTo(VoipCallState.Ended("no-answer"))
        runCurrent()
        assertEquals(ArkCallType.MISSED, callLog.records.single().type)
        assertEquals(1, missed.size)
    }

    @Test
    fun aCarrierFallbackIsNotLoggedAsAnArkCall() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = false))
        coordinator.startCall(link) { }
        runCurrent()
        assertTrue(callLog.records.isEmpty())
    }

    @Test
    fun theSystemAnswerAndDisconnectReachTheSession() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        telecom.lastAnswer!!()
        telecom.lastDisconnect!!()
        assertEquals(listOf("answer", "hangUp"), session.calls)
    }
}
