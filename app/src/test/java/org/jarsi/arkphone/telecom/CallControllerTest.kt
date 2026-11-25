package org.jarsi.arkphone.telecom

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

internal class FakeCallHandle(
    override val id: String = "call-1",
    override val number: String? = "0401234567",
    override val displayName: String? = null,
    override var telecomState: Int = Call.STATE_RINGING,
    override val connectTimeMillis: Long = 0,
    override val simAccountId: String? = null,
    override var disconnectError: DisconnectError? = null,
    override val viaArkCall: Boolean = false,
) : CallHandle {
    var answered = false
    var rejected = false
    var disconnected = false
    var held = false
    var dtmfStops = 0
    val dtmfStopped get() = dtmfStops > 0
    val dtmf = StringBuilder()
    override fun answer() { answered = true }
    override fun reject() { rejected = true }
    override fun disconnect() { disconnected = true }
    override fun hold() { held = true }
    override fun unhold() { held = false }
    override fun playDtmf(digit: Char) { dtmf.append(digit) }
    override fun stopDtmf() { dtmfStops++ }
}

private class FakeStopFuture : Future<Any?> {
    var cancelled = false
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        cancelled = true
        return true
    }
    override fun isCancelled() = cancelled
    override fun isDone() = cancelled
    override fun get(): Any? = null
    override fun get(timeout: Long, unit: TimeUnit): Any? = null
}

private class FakeAudioController : InCallAudioController {
    var muted = false
    var speaker = false
    override fun applyMuted(muted: Boolean) { this.muted = muted }
    override fun applyRoute(speaker: Boolean) { this.speaker = speaker }
}

class CallControllerTest {

    @Test
    fun digitsAndPendingStopsBelongToTheAddressedCall() {
        val controller = CallController()
        val stops = mutableListOf<Pair<Runnable, FakeStopFuture>>()
        controller.scheduleDtmfStop = { action -> FakeStopFuture().also { stops += action to it } }
        val first = FakeCallHandle(id = "first", telecomState = Call.STATE_ACTIVE)
        val second = FakeCallHandle(id = "second", telecomState = Call.STATE_HOLDING)
        controller.onCallAdded(first)
        controller.onCallAdded(second)
        controller.playDtmf(first.id, '1')
        controller.playDtmf(second.id, '#')
        controller.playDtmf(first.id, '*')
        controller.playDtmf("absent", '0')
        // The shared dial pad offers + on a long press. It has no DTMF tone.
        controller.playDtmf(first.id, '+')
        assertEquals(mapOf("first" to "1*", "second" to "#"), controller.dtmfDigits.value)
        assertEquals("1*", first.dtmf.toString())
        assertEquals("#", second.dtmf.toString())
        controller.onCallRemoved(first.id)
        assertEquals(false, stops[1].second.cancelled)
        assertEquals(mapOf("second" to "#"), controller.dtmfDigits.value)
        controller.onCallAdded(FakeCallHandle(id = first.id))
        assertEquals(null, controller.dtmfDigits.value[first.id])
        controller.onCallRemoved(second.id)
        assertTrue(controller.dtmfDigits.value.isEmpty())
    }

    @Test
    fun arkKeyPressesDoNotClaimToHaveSentDigits() {
        val controller = CallController()
        controller.onCallAdded(FakeCallHandle(viaArkCall = true))
        controller.playDtmf("call-1", '5')
        assertTrue(controller.dtmfDigits.value.isEmpty())
    }

    @Test
    fun lastNormalRemovalCleansTheBackgroundTaskEvenWithoutAnActivity() {
        var removals = 0
        val controller = CallController { removals++ }
        controller.onCallAdded(FakeCallHandle(id = "1"))
        controller.onCallAdded(FakeCallHandle(id = "2"))
        controller.onCallRemoved("1")
        assertEquals(0, removals)
        controller.onCallRemoved("2")
        assertEquals(1, removals)
    }

    @Test
    fun backgroundingDuringTheForegroundGraceCleansOnlyAfterANormalEnd() {
        var removals = 0
        val controller = CallController { removals++ }
        controller.onInCallUiVisibility(true)
        controller.onCallAdded(FakeCallHandle())
        controller.onCallRemoved("call-1")
        assertEquals(0, removals)
        controller.onInCallUiVisibility(false)
        assertEquals(1, removals)
        controller.onCallAdded(FakeCallHandle(disconnectError = DisconnectError("Radio unavailable", null)))
        controller.onCallRemoved("call-1")
        controller.onInCallUiVisibility(false)
        assertEquals(1, removals)
    }

    @Test
    fun publishesAddedCalls() {
        val controller = CallController()
        controller.onCallAdded(FakeCallHandle())
        val info = controller.calls.value.single()
        assertEquals("call-1", info.id)
        assertEquals(CallStatus.RINGING, info.status)
    }

    @Test
    fun publishesTheDisconnectError() {
        val controller = CallController()
        val handle = FakeCallHandle(telecomState = Call.STATE_DISCONNECTED)
        handle.disconnectError = DisconnectError("Radio off", "Turn off aeroplane mode.")
        controller.onCallAdded(handle)
        assertEquals(
            DisconnectError("Radio off", "Turn off aeroplane mode."),
            controller.calls.value.single().disconnectError,
        )
    }

    @Test
    fun keepsTheErrorSnapshotAfterRemovalUntilTheNextCall() {
        val controller = CallController()
        val handle = FakeCallHandle(telecomState = Call.STATE_DISCONNECTED)
        handle.disconnectError = DisconnectError("Radio off", null)
        controller.onCallAdded(handle)
        controller.onCallRemoved("call-1")
        assertEquals("Radio off", controller.endedCall.value?.disconnectError?.label)
        assertEquals(CallStatus.DISCONNECTED, controller.endedCall.value?.status)
        controller.onCallAdded(FakeCallHandle(id = "call-2"))
        assertEquals(null, controller.endedCall.value)
    }

    @Test
    fun clearingTheEndedSnapshotEmptiesIt() {
        val controller = CallController()
        val handle = FakeCallHandle(telecomState = Call.STATE_DISCONNECTED)
        handle.disconnectError = DisconnectError("Radio off", null)
        controller.onCallAdded(handle)
        controller.onCallRemoved("call-1")
        controller.clearEndedCall()
        assertEquals(null, controller.endedCall.value)
    }

    @Test
    fun normalHangUpsLeaveNoEndedSnapshot() {
        val controller = CallController()
        controller.onCallAdded(FakeCallHandle(telecomState = Call.STATE_DISCONNECTED))
        controller.onCallRemoved("call-1")
        assertEquals(null, controller.endedCall.value)
    }

    @Test
    fun answerDelegatesToHandle() {
        val controller = CallController()
        val handle = FakeCallHandle()
        controller.onCallAdded(handle)
        controller.answer("call-1")
        assertTrue(handle.answered)
    }

    @Test
    fun stateChangesArePublished() {
        val controller = CallController()
        val handle = FakeCallHandle()
        controller.onCallAdded(handle)
        handle.telecomState = Call.STATE_ACTIVE
        controller.onCallChanged()
        assertEquals(CallStatus.ACTIVE, controller.calls.value.single().status)
    }

    @Test
    fun removedCallsDisappear() {
        val controller = CallController()
        controller.onCallAdded(FakeCallHandle())
        controller.onCallRemoved("call-1")
        assertTrue(controller.calls.value.isEmpty())
    }

    @Test
    fun muteTogglesThroughAudioController() {
        val controller = CallController()
        val audio = FakeAudioController()
        controller.attachAudioController(AudioControllerOwner.CARRIER, audio)
        controller.toggleMute()
        assertTrue(audio.muted)
        controller.onAudioStateChanged(muted = true, speakerOn = false)
        assertTrue(controller.audio.value.muted)
    }

    @Test
    fun anEndedArkCallHandsAudioBackToTheCarrierController() {
        // A carrier call must not lose its mute and speaker buttons when a
        // concurrent ARK call attaches its own audio surface and then ends.
        val controller = CallController()
        val carrier = FakeAudioController()
        val ark = FakeAudioController()
        controller.attachAudioController(AudioControllerOwner.CARRIER, carrier)
        controller.attachAudioController(AudioControllerOwner.ARK, ark)
        controller.toggleMute()
        assertTrue(ark.muted)
        assertEquals(false, carrier.muted)
        controller.detachAudioController(AudioControllerOwner.ARK)
        controller.toggleMute()
        assertTrue(carrier.muted)
    }

    @Test
    fun aDestroyedCarrierServiceLeavesTheArkControllerAttached() {
        val controller = CallController()
        val carrier = FakeAudioController()
        val ark = FakeAudioController()
        controller.attachAudioController(AudioControllerOwner.CARRIER, carrier)
        controller.attachAudioController(AudioControllerOwner.ARK, ark)
        controller.detachAudioController(AudioControllerOwner.CARRIER)
        controller.toggleSpeaker()
        assertTrue(ark.speaker)
        assertEquals(false, carrier.speaker)
    }

    @Test
    fun detachingTheLastControllerLeavesTogglesInert() {
        val controller = CallController()
        val carrier = FakeAudioController()
        controller.attachAudioController(AudioControllerOwner.CARRIER, carrier)
        controller.detachAudioController(AudioControllerOwner.CARRIER)
        controller.toggleMute()
        assertEquals(false, carrier.muted)
    }

    @Test
    fun rejectDelegatesToHandle() {
        val controller = CallController()
        val handle = FakeCallHandle()
        controller.onCallAdded(handle)
        controller.reject("call-1")
        assertTrue(handle.rejected)
    }

    @Test
    fun hangUpDelegatesToHandle() {
        val controller = CallController()
        val handle = FakeCallHandle()
        controller.onCallAdded(handle)
        controller.hangUp("call-1")
        assertTrue(handle.disconnected)
    }

    @Test
    fun toggleHoldHoldsWhenActiveAndUnholdsWhenHolding() {
        val controller = CallController()
        val handle = FakeCallHandle(telecomState = Call.STATE_ACTIVE)
        controller.onCallAdded(handle)

        controller.toggleHold("call-1")
        assertTrue(handle.held)

        handle.telecomState = Call.STATE_HOLDING
        controller.toggleHold("call-1")
        assertEquals(false, handle.held)
    }

    @Test
    fun playDtmfPlaysDigitAndStopsAfterDelay() {
        val controller = CallController()
        val handle = FakeCallHandle()
        controller.onCallAdded(handle)

        controller.playDtmf("call-1", '5')

        assertEquals("5", handle.dtmf.toString())
        assertEquals(false, handle.dtmfStopped)

        val deadline = System.currentTimeMillis() + 1000
        while (!handle.dtmfStopped && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertTrue(handle.dtmfStopped)
    }

    @Test
    fun rapidDtmfInputEndsThePreviousToneInsteadOfCuttingTheNewOne() {
        val controller = CallController()
        val handle = FakeCallHandle()
        controller.onCallAdded(handle)
        val scheduled = mutableListOf<Pair<Runnable, FakeStopFuture>>()
        controller.scheduleDtmfStop = { action ->
            FakeStopFuture().also { scheduled += action to it }
        }

        controller.playDtmf("call-1", '1')
        assertEquals(1, scheduled.size)
        assertEquals(0, handle.dtmfStops)

        controller.playDtmf("call-1", '2')
        assertTrue(scheduled[0].second.cancelled)
        assertEquals(1, handle.dtmfStops)
        assertEquals("12", handle.dtmf.toString())

        scheduled[1].first.run()
        assertEquals(2, handle.dtmfStops)
    }

    @Test
    fun anArkCallIsPublishedAsOne() {
        val controller = CallController()
        controller.onCallAdded(
            object : CallHandle {
                override val id = "voip-1"
                override val number = "+358 44 5552841"
                override val displayName = "Jarsi"
                override val telecomState = Call.STATE_ACTIVE
                override val connectTimeMillis = 1_000L
                override val simAccountId: String? = null
                override val viaArkCall = true
                override fun answer() = Unit
                override fun reject() = Unit
                override fun disconnect() = Unit
                override fun hold() = Unit
                override fun unhold() = Unit
                override fun playDtmf(digit: Char) = Unit
                override fun stopDtmf() = Unit
            },
        )
        assertTrue(controller.calls.value.single().viaArkCall)
    }
}
