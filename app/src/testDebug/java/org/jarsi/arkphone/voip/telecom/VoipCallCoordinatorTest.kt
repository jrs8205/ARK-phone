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
        override val peerRinging = MutableStateFlow(false)
        override fun placeCall() { calls += "placeCall" }
        override fun answer() { calls += "answer" }
        override fun reject() { calls += "reject" }
        override fun hangUp() { calls += "hangUp" }
        override fun setMicEnabled(enabled: Boolean) { micOn = enabled }
        override fun notifyRinging() { calls += "notifyRinging" }
        fun moveTo(next: VoipCallState) { _state.value = next }
    }

    private class FakeTelecom(var accepts: Boolean = true) : VoipTelecom {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val answered = mutableListOf<String>()
        var lastAnswer: (() -> Unit)? = null
        var lastDisconnect: (() -> Unit)? = null
        var lastFailed: (() -> Unit)? = null
        /** Refuses inside add() itself, as Main.immediate lets addCall do. */
        var failInline = false
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
            if (failInline) onFailed()
            return true
        }
        override fun answered(id: String) { answered += id }
        override fun setActive(id: String) = Unit
        override fun requestSpeaker(id: String, speakerOn: Boolean) {
            speakerRequests += id to speakerOn
        }
        override fun remove(id: String, onReleased: () -> Unit) {
            removed += id
            if (holdRelease) pendingRelease = onReleased else onReleased()
        }
        fun releaseNow() {
            pendingRelease?.invoke()
            pendingRelease = null
        }
        val speakerRequests = mutableListOf<Pair<String, Boolean>>()
        var holdRelease = false
        var pendingRelease: (() -> Unit)? = null
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
        override fun silenceRinging(handle: VoipCallHandle) { events += "silenceRinging" }
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

    private class FakeRingback : RingbackController {
        var playing = false
        var startCount = 0
        override fun start() {
            playing = true
            startCount++
        }
        override fun stop() { playing = false }
    }

    private val session = FakeSession()
    private val telecom = FakeTelecom()
    private val ui = FakeUi()
    private val callLog = FakeCallLog()
    private val ringback = FakeRingback()
    private val missed = mutableListOf<ArkCallRecord>()
    private val createdCallIds = mutableListOf<String?>()

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
        arkEnabled: suspend () -> Boolean = { true },
    ) = VoipCallCoordinator(
        reachCheck = { code, timeout -> reach.reach(code, timeout) },
        sessionFactory = { _, _, _, _, callId, _ ->
            createdCallIds += callId
            session
        },
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
        arkCallsEnabled = arkEnabled,
        ringback = ringback,
    )

    @Test
    fun anOutgoingCallPlaysRingbackWhileThePeerRings() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { }
        runCurrent()
        assertFalse(ringback.playing)
        session.peerRinging.value = true
        runCurrent()
        assertTrue(ringback.playing)
        session.peerRinging.value = false
        runCurrent()
        assertFalse(ringback.playing)
        assertEquals(1, ringback.startCount)
    }

    @Test
    fun finishingTheCallStopsRingbackEvenIfThePeerFlagStaysUp() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { }
        runCurrent()
        session.peerRinging.value = true
        runCurrent()
        assertTrue(ringback.playing)
        session.moveTo(VoipCallState.Ended("peer-hangup"))
        runCurrent()
        assertFalse(ringback.playing)
    }

    @Test
    fun anIncomingCallNeverPlaysRingback() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "sdp"))
        runCurrent()
        session.peerRinging.value = true
        runCurrent()
        assertFalse(ringback.playing)
    }

    @Test
    fun theRingingPhoneTellsTheCallerOnceItsSurfacesAreUp() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "sdp"))
        runCurrent()
        assertTrue(session.calls.contains("notifyRinging"))
        assertTrue(ui.events.contains("showIncoming"))
    }

    @Test
    fun anInlineTelecomRefusalNeverNotifiesTheCaller() = runTest {
        telecom.failInline = true
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "sdp"))
        runCurrent()
        assertFalse(session.calls.contains("notifyRinging"))
    }

    @Test
    fun aRefusingPlatformMeansTheCallerMustUseTheCarrier() = runTest {
        telecom.accepts = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        assertFalse(coordinator.startCall(link) { })
        assertTrue(session.calls.isEmpty())
    }

    @Test
    fun theMasterSwitchIsReadFromLoadedSettingsNotTheDefault() = runTest {
        // Cold FCM start: the link cache can be ready before DataStore's
        // first emission. The switch must wait for the real value — the
        // default is true, and a disabled phone would ring anyway.
        val coordinator = coordinator(
            backgroundScope,
            FakeReach(reachable = true),
            arkEnabled = {
                kotlinx.coroutines.delay(1_000)
                false
            },
        )
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "sdp"))
        runCurrent()
        advanceTimeBy(1_100)
        runCurrent()
        assertFalse(ui.events.contains("added"))
    }

    @Test
    fun anIncomingSessionIsScopedToTheOffersCallId() = runTest {
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(
            IncomingArkCall("ARK-BBBB-BBBB", "sdp", callId = "call-x"),
        )
        runCurrent()
        assertEquals(listOf<String?>("call-x"), createdCallIds)
    }

    @Test
    fun anInlineTelecomRefusalOnAnIncomingCallNeverBlipsTheRing() = runTest {
        telecom.failInline = true
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "sdp"))
        runCurrent()
        // The platform refused before ring() resumed: the phone must not
        // flash a ring, announcement, or call screen for the dead call.
        assertFalse(ui.events.contains("showIncoming"))
        assertFalse(ui.events.contains("openCallScreen"))
        assertTrue(session.calls.contains("hangUp"))
        // The state observer must still run, so the Ended state finishes
        // the call and frees the one-call slot.
        session.moveTo(VoipCallState.Ended("local-hangup"))
        runCurrent()
        assertEquals(listOf("voip-in-ARK-BBBB-BBBB"), telecom.removed)
    }

    @Test
    fun anInlineTelecomRefusalLeavesNoZombieCall() = runTest {
        var fellBack = 0
        telecom.failInline = true
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        assertTrue(coordinator.startCall(link) { fellBack++ })
        runCurrent()
        assertEquals(1, fellBack)
        // The handle was finished before add() returned: adding it to the UI
        // afterwards would leave a call nothing can ever remove.
        assertFalse(ui.events.contains("added"))
        assertFalse(ui.events.contains("openCallScreen"))
    }

    @Test
    fun anUnreachablePeerFallsBackToTheCarrierWithoutRinging() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = false))
        assertTrue(coordinator.startCall(link) { fellBack = true })
        runCurrent()
        assertTrue(fellBack)
        assertFalse(session.calls.contains("placeCall"))
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
    fun aRingingCallIsNotTornDownAtFifteenSeconds() = runTest {
        // The callee's phone is audibly ringing; a normal answer takes
        // 20-30 s. Cutting over to the carrier mid-ring turns one call into
        // a confusing double ring on both ends.
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        session.peerRinging.value = true
        runCurrent()
        advanceTimeBy(VOIP_CONNECT_TIMEOUT_MS + 100)
        runCurrent()
        assertFalse(fellBack)
    }

    @Test
    fun aRingingCallStillFallsBackOnceTheRingWindowCloses() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        session.peerRinging.value = true
        runCurrent()
        advanceTimeBy(VOIP_RING_TIMEOUT_MS + 100)
        runCurrent()
        assertTrue(fellBack)
        assertTrue(session.calls.contains("hangUp"))
    }

    @Test
    fun anUnansweredIncomingRingOutlivesTheCallersRingWindow() = runTest {
        // The callee's own guard must fire only after the caller's ring
        // window has closed — otherwise the callee hangs up first and the
        // caller never falls back to the carrier.
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "sdp"))
        runCurrent()
        advanceTimeBy(VOIP_RING_TIMEOUT_MS + 100)
        runCurrent()
        assertFalse(session.calls.contains("hangUp"))
        advanceTimeBy(VOIP_INCOMING_RING_TIMEOUT_MS - VOIP_RING_TIMEOUT_MS)
        runCurrent()
        assertTrue(session.calls.contains("hangUp"))
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
        assertEquals(listOf("notifyRinging", "answer", "hangUp"), session.calls)
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
        // One call from the user's point of view: the carrier row is the row.
        assertTrue(callLog.records.isEmpty())
    }

    @Test
    fun aTelecomWithdrawalEndsTheSessionBeforeTheCarrierDials() = runTest {
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = true))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        telecom.lastFailed!!()
        runCurrent()
        assertTrue(fellBack)
        // The peer must stop ringing and the adapter must close — cancelling
        // the scope alone does neither.
        assertTrue(session.calls.contains("hangUp"))
    }

    @Test
    fun theCarrierDialsOnlyAfterTelecomReleasedTheArkCall() = runTest {
        // Field-hit 2026-08-09 16:56: the fallback raced the asynchronous
        // Telecom disconnect and was rejected as a second concurrent call.
        telecom.holdRelease = true
        var fellBack = false
        val coordinator = coordinator(backgroundScope, FakeReach(reachable = false))
        coordinator.startCall(link) { fellBack = true }
        runCurrent()
        assertFalse(fellBack)
        telecom.releaseNow()
        assertTrue(fellBack)
    }

    @Test
    fun aDisabledMasterSwitchDropsIncomingArkCalls() = runTest {
        val coordinator =
            coordinator(backgroundScope, FakeReach(reachable = true), arkEnabled = { false })
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        assertTrue(telecom.added.isEmpty())
        assertTrue(ui.events.isEmpty())
    }

    @Test
    fun anIncomingCallWithoutMicPermissionNeverRings() = runTest {
        val coordinator =
            coordinator(backgroundScope, FakeReach(reachable = true), hasMic = { false })
        coordinator.onIncoming(IncomingArkCall("ARK-BBBB-BBBB", "v=0"))
        runCurrent()
        assertTrue(telecom.added.isEmpty())
        assertTrue(ui.events.isEmpty())
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
        assertEquals(listOf("notifyRinging", "answer"), session.calls)
        // The ringtone dies with the answer, not with the media connecting.
        assertTrue(ui.events.contains("silenceRinging"))
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
