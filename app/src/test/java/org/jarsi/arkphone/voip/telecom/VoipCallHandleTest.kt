package org.jarsi.arkphone.voip.telecom

import android.telecom.Call
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.VoipCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class VoipCallHandleTest {

    private class RecordingActions : VoipCallActions {
        val calls = mutableListOf<String>()
        override fun answer() { calls += "answer" }
        override fun reject() { calls += "reject" }
        override fun hangUp() { calls += "hangUp" }
    }

    private val actions = RecordingActions()

    private fun handle(direction: VoipCallDirection, now: Long = 7_000L) = VoipCallHandle(
        id = "voip-1",
        number = "+358 44 5552841",
        displayName = "Jarsi",
        direction = direction,
        actions = actions,
        clock = Clock { now },
    )

    @Test
    fun anOutgoingCallStartsAsConnectingThenDials() {
        val handle = handle(VoipCallDirection.OUTGOING)
        assertEquals(Call.STATE_CONNECTING, handle.telecomState)
        handle.onState(VoipCallState.Connecting)
        assertEquals(Call.STATE_DIALING, handle.telecomState)
    }

    @Test
    fun anIncomingCallRingsFromTheStart() {
        val handle = handle(VoipCallDirection.INCOMING)
        assertEquals(Call.STATE_RINGING, handle.telecomState)
        handle.onState(VoipCallState.Ringing("v=0"))
        assertEquals(Call.STATE_RINGING, handle.telecomState)
        handle.onState(VoipCallState.Connecting)
        assertEquals(Call.STATE_CONNECTING, handle.telecomState)
    }

    @Test
    fun theCallBecomesActiveAndStampsItsConnectTime() {
        val handle = handle(VoipCallDirection.OUTGOING)
        assertEquals(0L, handle.connectTimeMillis)
        handle.onState(VoipCallState.InCall)
        assertEquals(Call.STATE_ACTIVE, handle.telecomState)
        assertEquals(7_000L, handle.connectTimeMillis)
    }

    @Test
    fun theConnectTimeIsStampedOnceAndSurvivesTheHangUp() {
        val handle = handle(VoipCallDirection.OUTGOING)
        handle.onState(VoipCallState.InCall)
        handle.onState(VoipCallState.Ended("local-hangup"))
        assertEquals(Call.STATE_DISCONNECTED, handle.telecomState)
        assertEquals(7_000L, handle.connectTimeMillis)
    }

    @Test
    fun aCallThatEndedBeforeItConnectedHasNoConnectTime() {
        val handle = handle(VoipCallDirection.OUTGOING)
        handle.onState(VoipCallState.Ended("no-answer"))
        assertEquals(0L, handle.connectTimeMillis)
    }

    @Test
    fun theHandleIsMarkedAsAnArkCallAndCarriesNoSim() {
        val handle = handle(VoipCallDirection.INCOMING)
        assertTrue(handle.viaArkCall)
        assertEquals(null, handle.simAccountId)
        assertEquals(null, handle.disconnectError)
    }

    @Test
    fun theControlActionsReachTheSession() {
        val handle = handle(VoipCallDirection.INCOMING)
        handle.answer()
        handle.reject()
        handle.disconnect()
        assertEquals(listOf("answer", "reject", "hangUp"), actions.calls)
    }

    @Test
    fun holdAndDtmfAreInertInPhaseOne() {
        val handle = handle(VoipCallDirection.INCOMING)
        handle.hold()
        handle.unhold()
        handle.playDtmf('5')
        handle.stopDtmf()
        assertTrue(actions.calls.isEmpty())
    }
}
