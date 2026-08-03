package org.jarsi.arkphone.telecom

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

private class FakeCallHandle(
    override val id: String = "call-1",
    override val number: String? = "0401234567",
    override val displayName: String? = null,
    override var telecomState: Int = Call.STATE_RINGING,
    override val connectTimeMillis: Long = 0,
    override val simAccountId: String? = null,
    override var disconnectError: DisconnectError? = null,
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
        controller.audioController = audio
        controller.toggleMute()
        assertTrue(audio.muted)
        controller.onAudioStateChanged(muted = true, speakerOn = false)
        assertTrue(controller.audio.value.muted)
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

}
