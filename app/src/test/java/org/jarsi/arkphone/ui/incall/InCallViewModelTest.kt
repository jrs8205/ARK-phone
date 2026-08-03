package org.jarsi.arkphone.ui.incall

import android.telecom.Call
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallHandle
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.telecom.DisconnectError
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeSimAccountRepository
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
    override val simAccountId: String? = null
    override var disconnectError: DisconnectError? = null
    var answered = false
    var rejected = false
    override fun answer() { answered = true }
    override fun reject() { rejected = true }
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
    private val silencedCalls = mutableListOf<String>()
    private val sentMessages = mutableListOf<Pair<String, String>>()
    private val toasts = mutableListOf<Int>()

    private fun viewModel(
        controller: CallController,
        contacts: FakeContactsRepository = FakeContactsRepository(),
        callLog: FakeCallLogRepository = FakeCallLogRepository(),
        smsSucceeds: Boolean = true,
    ) = InCallViewModel(
        controller,
        contacts,
        callLog,
        FakeSimAccountRepository(),
        clock,
        { info -> silencedCalls.add(info.id) },
        { number, message ->
            sentMessages.add(number to message)
            smsSucceeds
        },
        { resId -> toasts.add(resId) },
    )

    @Test
    fun exposesPrimaryCall() = runTest {
        val controller = CallController()
        val handle = TestCallHandle()
        controller.onCallAdded(handle)
        val viewModel = viewModel(controller)
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
        val viewModel = viewModel(controller)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.call == null) state = awaitItem()
            viewModel.onAnswer()
            assertTrue(handle.answered)
        }
    }

    @Test
    fun keepsTheDisconnectErrorAfterTelecomRemovesTheCall() = runTest {
        val controller = CallController()
        val handle = TestCallHandle(telecomState = Call.STATE_DIALING)
        controller.onCallAdded(handle)
        val viewModel = viewModel(controller)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.call == null) state = awaitItem()
            handle.telecomState = Call.STATE_DISCONNECTED
            handle.disconnectError = DisconnectError(
                label = "Radio pois käytöstä",
                description = "Poista lentokonetila käytöstä.",
            )
            controller.onCallChanged()
            controller.onCallRemoved(handle.id)
            while (state.call?.disconnectError == null) state = awaitItem()
            assertEquals(CallStatus.DISCONNECTED, state.call?.status)
            assertEquals("Radio pois käytöstä", state.call?.disconnectError?.label)
        }
    }

    @Test
    fun exposesCallerPhotoFromContactLookup() = runTest {
        val controller = CallController()
        controller.onCallAdded(TestCallHandle())
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["0401234567"] = ContactMatch("Alice", "content://photo/1")
        }
        val viewModel = viewModel(controller, contacts = contacts)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.callerPhotoUri == null) state = awaitItem()
            assertEquals("content://photo/1", state.callerPhotoUri)
            assertTrue(state.knownCaller)
        }
    }

    @Test
    fun silenceStopsTheRingWithoutRejecting() = runTest {
        val controller = CallController()
        val handle = TestCallHandle()
        controller.onCallAdded(handle)
        val viewModel = viewModel(controller)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.call == null) state = awaitItem()
            viewModel.onSilence()
            assertEquals(listOf("call-1"), silencedCalls)
            assertEquals(false, handle.rejected)
        }
    }

    @Test
    fun rejectWithMessageSendsTheSmsAndRejects() = runTest {
        val controller = CallController()
        val handle = TestCallHandle()
        controller.onCallAdded(handle)
        val viewModel = viewModel(controller)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.call == null) state = awaitItem()
            viewModel.onRejectWithMessage("Soitan kohta.")
            assertEquals(listOf("0401234567" to "Soitan kohta."), sentMessages)
            assertTrue(handle.rejected)
            assertTrue(toasts.isEmpty())
        }
    }

    @Test
    fun failedRejectMessageStillRejectsAndReportsTheFailure() = runTest {
        val controller = CallController()
        val handle = TestCallHandle()
        controller.onCallAdded(handle)
        val viewModel = viewModel(controller, smsSucceeds = false)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.call == null) state = awaitItem()
            viewModel.onRejectWithMessage("Soitan kohta.")
            assertTrue(handle.rejected)
            assertEquals(listOf(R.string.incall_reject_message_failed), toasts)
        }
    }

    @Test
    fun contactNameFromTheLookupBacksUpTheTelecomName() = runTest {
        // On API 26–29 telecom never provides the contact name, so the
        // screen falls back to the name resolved from the contacts lookup.
        val controller = CallController()
        controller.onCallAdded(TestCallHandle())
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["0401234567"] = ContactMatch("Alice", null)
        }
        val viewModel = viewModel(controller, contacts = contacts)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.callerDisplayName == null) state = awaitItem()
            assertEquals("Alice", state.callerDisplayName)
        }
    }

    @Test
    fun callInfoSurvivesRemovalUntilTheScreenCloses() = runTest {
        val controller = CallController()
        controller.onCallAdded(TestCallHandle())
        val viewModel = viewModel(controller)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.call == null) state = awaitItem()
            controller.onCallRemoved("call-1")
            while (state.call?.status != CallStatus.DISCONNECTED) state = awaitItem()
            assertEquals("0401234567", state.call?.number)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unknownCallerAndLastCallAreExposed() = runTest {
        val controller = CallController()
        controller.onCallAdded(TestCallHandle())
        val callLog = FakeCallLogRepository().apply {
            entries.value = listOf(
                CallLogEntry(
                    id = 9,
                    number = "0401234567",
                    displayName = null,
                    type = CallType.OUTGOING,
                    timestampMillis = 55_000L,
                    durationSeconds = 10,
                ),
            )
        }
        val viewModel = viewModel(controller, callLog = callLog)
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.lastCalledMillis == null) state = awaitItem()
            assertEquals(55_000L, state.lastCalledMillis)
            assertEquals(false, state.knownCaller)
        }
    }
}
