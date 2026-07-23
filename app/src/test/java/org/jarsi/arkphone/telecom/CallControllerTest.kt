package org.jarsi.arkphone.telecom

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeCallHandle(
    override val id: String = "call-1",
    override val number: String? = "0401234567",
    override val displayName: String? = null,
    override var telecomState: Int = Call.STATE_RINGING,
    override val connectTimeMillis: Long = 0,
) : CallHandle {
    var answered = false
    var rejected = false
    var disconnected = false
    var held = false
    val dtmf = StringBuilder()
    override fun answer() { answered = true }
    override fun reject() { rejected = true }
    override fun disconnect() { disconnected = true }
    override fun hold() { held = true }
    override fun unhold() { held = false }
    override fun playDtmf(digit: Char) { dtmf.append(digit) }
    override fun stopDtmf() {}
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
}
