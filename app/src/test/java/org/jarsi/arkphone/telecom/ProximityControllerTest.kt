package org.jarsi.arkphone.telecom

import android.telecom.Call
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeProximityLock : ProximityLock {
    var held = false
    override fun acquire() {
        held = true
    }
    override fun release() {
        held = false
    }
}

private class ProximityFakeCallHandle(
    override val id: String = "call-1",
    override var telecomState: Int = Call.STATE_ACTIVE,
) : CallHandle {
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

class ProximityControllerTest {

    // The decision itself.

    @Test
    fun holdsForActiveCallOnEarpiece() {
        assertTrue(
            shouldHoldProximityLock(
                listOf(CallStatus.ACTIVE),
                earpieceRoute = true,
                inCallUiVisible = true,
            ),
        )
    }

    @Test
    fun holdsForDialingCallOnEarpiece() {
        assertTrue(
            shouldHoldProximityLock(
                listOf(CallStatus.DIALING),
                earpieceRoute = true,
                inCallUiVisible = true,
            ),
        )
    }

    @Test
    fun holdsForHeldCallOnEarpiece() {
        assertTrue(
            shouldHoldProximityLock(
                listOf(CallStatus.HOLDING),
                earpieceRoute = true,
                inCallUiVisible = true,
            ),
        )
    }

    @Test
    fun ignoresRingingCall() {
        assertFalse(
            shouldHoldProximityLock(
                listOf(CallStatus.RINGING),
                earpieceRoute = true,
                inCallUiVisible = true,
            ),
        )
    }

    @Test
    fun ignoresNonEarpieceRoutes() {
        assertFalse(
            shouldHoldProximityLock(
                listOf(CallStatus.ACTIVE),
                earpieceRoute = false,
                inCallUiVisible = true,
            ),
        )
    }

    @Test
    fun ignoresWhenNoCalls() {
        assertFalse(
            shouldHoldProximityLock(emptyList(), earpieceRoute = true, inCallUiVisible = true),
        )
    }

    @Test
    fun releasesTheScreenToAUserWhoLeftTheCallUi() {
        // Another app on top mid-call means the user is USING the display;
        // an armed sensor there blacks it on every reach for the status bar.
        assertFalse(
            shouldHoldProximityLock(
                listOf(CallStatus.ACTIVE),
                earpieceRoute = true,
                inCallUiVisible = false,
            ),
        )
    }

    // The controller wiring: call and audio changes drive the lock. The
    // collector runs on a scope sharing the test scheduler — backgroundScope
    // is not pumped by advanceUntilIdle (learned on the SIM round).

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun proximityTest(
        body: TestScope.(CallController, FakeProximityLock) -> Unit,
    ) = runTest {
        val scope = CoroutineScope(coroutineContext + Job())
        try {
            val controller = CallController()
            val lock = FakeProximityLock()
            ProximityController(controller, lock, scope)
            body(controller, lock)
        } finally {
            scope.cancel()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun acquiresWhenCallActiveOnEarpiece() = proximityTest { controller, lock ->
        controller.onInCallUiVisibility(true)
        controller.onAudioStateChanged(muted = false, speakerOn = false, earpiece = true)
        controller.onCallAdded(ProximityFakeCallHandle())
        advanceUntilIdle()
        assertTrue(lock.held)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun releasesWhenTheUserLeavesTheCallScreen() = proximityTest { controller, lock ->
        controller.onInCallUiVisibility(true)
        controller.onAudioStateChanged(muted = false, speakerOn = false, earpiece = true)
        controller.onCallAdded(ProximityFakeCallHandle())
        advanceUntilIdle()
        assertTrue(lock.held)
        controller.onInCallUiVisibility(false)
        advanceUntilIdle()
        assertFalse(lock.held)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun releasesWhenRouteLeavesEarpiece() = proximityTest { controller, lock ->
        controller.onInCallUiVisibility(true)
        controller.onAudioStateChanged(muted = false, speakerOn = false, earpiece = true)
        controller.onCallAdded(ProximityFakeCallHandle())
        advanceUntilIdle()
        assertTrue(lock.held)
        controller.onAudioStateChanged(muted = false, speakerOn = true, earpiece = false)
        advanceUntilIdle()
        assertFalse(lock.held)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun releasesWhenCallEnds() = proximityTest { controller, lock ->
        controller.onInCallUiVisibility(true)
        controller.onAudioStateChanged(muted = false, speakerOn = false, earpiece = true)
        val handle = ProximityFakeCallHandle()
        controller.onCallAdded(handle)
        advanceUntilIdle()
        assertTrue(lock.held)
        controller.onCallRemoved(handle.id)
        advanceUntilIdle()
        assertFalse(lock.held)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun neverAcquiresForRingingOnlyCall() = proximityTest { controller, lock ->
        controller.onInCallUiVisibility(true)
        controller.onAudioStateChanged(muted = false, speakerOn = false, earpiece = true)
        controller.onCallAdded(ProximityFakeCallHandle(telecomState = Call.STATE_RINGING))
        advanceUntilIdle()
        assertFalse(lock.held)
    }
}
