package org.jarsi.arkphone.voip.telecom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.VoipCallState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArkCallRecordTest {

    private object NoActions : VoipCallActions {
        override fun answer() = Unit
        override fun reject() = Unit
        override fun hangUp() = Unit
    }

    private fun handle(direction: VoipCallDirection, connectedAt: Long?) = VoipCallHandle(
        id = "voip-1",
        number = "+358 44 5552841",
        displayName = "Jarsi",
        direction = direction,
        actions = NoActions,
        clock = Clock { connectedAt ?: 0L },
    ).apply { if (connectedAt != null) onState(VoipCallState.InCall) }

    @Test
    fun anAnsweredIncomingCallIsLoggedWithItsDuration() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.INCOMING, connectedAt = 10_000L),
            direction = VoipCallDirection.INCOMING,
            endReason = "peer-hangup",
            endedAtMillis = 40_000L,
        )
        assertEquals(ArkCallType.INCOMING, record.type)
        assertEquals(10_000L, record.startedAtMillis)
        assertEquals(30L, record.durationSeconds)
        assertEquals("+358 44 5552841", record.number)
        assertEquals("Jarsi", record.displayName)
    }

    @Test
    fun anUnansweredIncomingCallIsAMissedCall() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.INCOMING, connectedAt = null),
            direction = VoipCallDirection.INCOMING,
            endReason = "no-answer",
            endedAtMillis = 40_000L,
        )
        assertEquals(ArkCallType.MISSED, record.type)
        assertEquals(40_000L, record.startedAtMillis)
        assertEquals(0L, record.durationSeconds)
    }

    @Test
    fun anIncomingCallTheUserDeclinedIsNotAMissedCall() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.INCOMING, connectedAt = null),
            direction = VoipCallDirection.INCOMING,
            endReason = "local-reject",
            endedAtMillis = 40_000L,
        )
        assertEquals(ArkCallType.INCOMING, record.type)
    }

    @Test
    fun anAnsweredCallWhoseMediaNeverConnectedIsNotAMissedCall() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.INCOMING, connectedAt = null),
            direction = VoipCallDirection.INCOMING,
            endReason = "connection-failed",
            endedAtMillis = 40_000L,
            answeredByUser = true,
        )
        assertEquals(ArkCallType.INCOMING, record.type)
        assertEquals(0L, record.durationSeconds)
    }

    @Test
    fun anOutgoingCallThatWasNeverAnsweredIsStillAnOutgoingCall() {
        val record = arkCallRecordOf(
            handle = handle(VoipCallDirection.OUTGOING, connectedAt = null),
            direction = VoipCallDirection.OUTGOING,
            endReason = "no-answer",
            endedAtMillis = 40_000L,
        )
        assertEquals(ArkCallType.OUTGOING, record.type)
        assertEquals(0L, record.durationSeconds)
    }
}
