package org.jarsi.arkphone.voip.telecom

import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoipCallCoordinatorTest {

    private class FakeSession : VoipMediaSession {
        val calls = mutableListOf<String>()
        var micOn = true
        private val _state = MutableStateFlow<VoipCallState>(VoipCallState.Idle)
        override val state: StateFlow<VoipCallState> = _state
        override fun placeCall() { calls += "placeCall" }
        override fun answer() { calls += "answer" }
        override fun reject() { calls += "reject" }
        override fun hangUp() { calls += "hangUp" }
        override fun setMicEnabled(enabled: Boolean) { micOn = enabled }
        fun moveTo(next: VoipCallState) { _state.value = next }
    }

    private class FakeTelecom(var accepts: Boolean = true) : VoipTelecom {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val answered = mutableListOf<String>()
        var lastAnswer: (() -> Unit)? = null
        var lastDisconnect: (() -> Unit)? = null
        var lastFailed: (() -> Unit)? = null
        override fun add(
            handle: VoipCallHandle,
            onSystemAnswer: () -> Unit,
            onSystemDisconnect: () -> Unit,
            onFailed: () -> Unit,
        ): Boolean {
            if (!accepts) return false
            added += handle.id
            lastAnswer = onSystemAnswer
            lastDisconnect = onSystemDisconnect
            lastFailed = onFailed
            return true
        }
        override fun answered(id: String) { answered += id }
        override fun setActive(id: String) = Unit
        override fun requestSpeaker(id: String, speakerOn: Boolean) {
            speakerRequests += id to speakerOn
        }
        override fun remove(id: String) { removed += id }
        val speakerRequests = mutableListOf<Pair<String, Boolean>>()
    }

    private class FakeUi : VoipCallUi {
        val events = mutableListOf<String>()
        var lastHandle: VoipCallHandle? = null
        override fun added(handle: VoipCallHandle) {
            events += "added"
            lastHandle = handle
        }
        override fun changed() { events += "changed" }
        override fun removed(id: String) { events += "removed" }
        override fun showIncoming(handle: VoipCallHandle) { events += "showIncoming" }
        override fun showOngoing(handle: VoipCallHandle) { events += "showOngoing" }
        override fun clearNotification() { events += "clearNotification" }
        override fun openCallScreen() { events += "openCallScreen" }
        override fun startCallService() { events += "startCallService" }
        override fun stopCallService() { events += "stopCallService" }
        var audioController: org.jarsi.arkphone.telecom.InCallAudioController? = null
        override fun attachAudioControls(
            controller: org.jarsi.arkphone.telecom.InCallAudioController,
        ) {
            audioController = controller
        }
        override fun detachAudioControls() { audioController = null }
        override fun audioStateChanged(muted: Boolean, speakerOn: Boolean) {
            events += "audio muted=$muted speaker=$speakerOn"
        }
    }

    private class FakeCallLog : ArkCallLog {
        val records = mutableListOf<ArkCallRecord>()
        override fun record(record: ArkCallRecord) { records += record }
        override fun unreadMissedCount(): Int = records.count { it.type == ArkCallType.MISSED }
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
        blockCheck: suspend (String?) -> Boolean = { false },
        hasMic: () -> Boolean = { true },
    ) = VoipCallCoordinator(
        reachCheck = { code, timeout -> reach.reach(code, timeout) },
        sessionFactory = { _, _, _, _ -> session },
        telecom = telecom,
        ui = ui,
        nicknameForCode = nicknameFor,
        numberForCode = numberFor,
        callLog = callLog,
        missedCalls = { missed += it },
        clock = Clock { 1_000L },
        scope = scope,
        blockCheck = blockCheck,
        hasMicPermission = hasMic,
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
        assertTrue(ui.events.contains("openCallScreen"))
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

    @Test
    fun anUnlinkedCallerNeverRings() = runTest {
        val coordinator = coordinator(
            backgroundScope,
            FakeReach(reachable = true),
            nicknameFor = { null },
            numberFor = { null },
        )
        coordinator.onIncoming(IncomingArkCall("ARK-EEEE-EEEE", "v=0"))
        runCurrent()
        assertTrue(telecom.added.isEmpty())
        assertTrue(ui.events.isEmpty())
    }

    @Test
    fun aBlockedNumberNeverRings() = runTest {
        val coordinator = coordinator(
            backgroundScope,
            FakeReach(reachable = true),
            blockCheck = { true },
        )
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        assertTrue(telecom.added.isEmpty())
        assertTrue(ui.events.isEmpty())
    }

    @Test
    fun withoutMicPermissionTheCarrierPlacesTheCall() = runTest {
        val coordinator =
            coordinator(backgroundScope, FakeReach(reachable = true), hasMic = { false })
        assertFalse(coordinator.startCall(link) { })
        assertTrue(session.calls.isEmpty())
        assertTrue(telecom.added.isEmpty())
    }

    @Test
    fun anEarlySessionFailureFallsBackToTheCarrier() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        session.moveTo(VoipCallState.Ended("no-turn"))
        runCurrent()
        assertTrue(fellBack)
        assertTrue(ui.events.contains("removed"))
    }

    @Test
    fun aTelecomWithdrawalOnAnOutgoingCallFallsBack() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        telecom.lastFailed!!()
        runCurrent()
        assertTrue(fellBack)
    }

    @Test
    fun answeringInArkUiAlsoAnswersTheTelecomCall() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        ui.lastHandle!!.answer()
        assertEquals(listOf("voip-in-ARK-BBBB-BBBB"), telecom.answered)
        assertEquals(listOf("answer"), session.calls)
    }

    @Test
    fun theForegroundServiceCoversIceNegotiation() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        assertFalse(ui.events.contains("startCallService"))
        session.moveTo(VoipCallState.Connecting)
        runCurrent()
        assertTrue(ui.events.contains("startCallService"))
    }

    @Test
    fun theMuteAndSpeakerButtonsActOnTheArkCall() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        val audio = ui.audioController!!
        audio.applyMuted(true)
        assertFalse(session.micOn)
        audio.applyRoute(true)
        assertEquals(listOf("voip-in-ARK-BBBB-BBBB" to true), telecom.speakerRequests)
        session.moveTo(VoipCallState.Ended("peer-hangup"))
        runCurrent()
        assertTrue(ui.audioController == null)
    }

    @Test
    fun anAnsweredButNeverConnectedCallIsNotMissed() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        telecom.lastAnswer!!()
        session.moveTo(VoipCallState.Ended("connection-failed"))
        runCurrent()
        assertEquals(ArkCallType.INCOMING, callLog.records.single().type)
        assertTrue(missed.isEmpty())
    }
}
