package org.jarsi.arkphone.ui.incall

import android.telecom.Call
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallHandle
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.jarsi.arkphone.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class TestCallHandle(
    override var telecomState: Int = Call.STATE_RINGING,
) : CallHandle {
    override val id = "call-1"
    override val number = "0401234567"
    override val displayName: String? = null
    override val connectTimeMillis = 0L
    var answered = false
    override fun answer() { answered = true }
    override fun reject() {}
    override fun disconnect() {}
    override fun hold() {}
    override fun unhold() {}
    override fun playDtmf(digit: Char) {}
    override fun stopDtmf() {}
}

class InCallViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock { 100_000L }

    @Test
    fun exposesPrimaryCall() = runTest {
        val controller = CallController()
        val handle = TestCallHandle()
        controller.onCallAdded(handle)
        val viewModel = InCallViewModel(controller, clock)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.call == null) state = awaitItem()
            assertEquals(CallStatus.RINGING, state.call?.status)
        }
    }

    @Test
    fun answerActsOnPrimaryCall() = runTest {
        val controller = CallController()
        val handle = TestCallHandle()
        controller.onCallAdded(handle)
        val viewModel = InCallViewModel(controller, clock)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.call == null) state = awaitItem()
            viewModel.onAnswer()
            assertTrue(handle.answered)
        }
    }
}
