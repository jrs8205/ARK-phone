package org.jarsi.arkphone.ui.dialpad

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeSpeedDialRepository
import org.jarsi.arkphone.testing.MainDispatcherRule
import org.jarsi.arkphone.util.DialingRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DialpadViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val contacts = FakeContactsRepository()
    private val speedDial = FakeSpeedDialRepository()
    private val callLog = FakeCallLogRepository()

    private fun viewModel(
        matchDispatcher: kotlinx.coroutines.CoroutineDispatcher = mainDispatcherRule.dispatcher,
    ) = DialpadViewModel(contacts, speedDial, callLog, DialingRegion { "FI" }, matchDispatcher)

    @Test
    fun pasteKeepsOnlyPhoneCharacters() = runTest {
        val viewModel = viewModel()
        viewModel.onPaste("Soita: +358 (44) 555-2841 heti!")
        viewModel.uiState.test {
            skipItems(1)
            assertEquals("+358445552841", awaitItem().number)
        }
    }

    @Test
    fun displayNumberIsFormattedForTheDialingRegion() = runTest {
        val viewModel = viewModel()
        viewModel.setNumber("0401234567")
        viewModel.uiState.test {
            skipItems(1)
            assertEquals(
                formatDialpadNumber("0401234567", "FI"),
                awaitItem().displayNumber,
            )
        }
    }

    @Test
    fun starAndHashNumbersAreShownAsTyped() {
        assertEquals("*#06#", formatDialpadNumber("*#06#", "FI"))
        assertEquals("", formatDialpadNumber("", "FI"))
    }

    @Test
    fun speedDialAssignmentsFlowIntoTheUiState() = runTest {
        speedDial.state.value = mapOf(3 to "+358 44 5552841")
        val viewModel = viewModel()
        viewModel.uiState.test {
            skipItems(1)
            assertEquals(mapOf(3 to "+358 44 5552841"), awaitItem().speedDial)
        }
    }

    @Test
    fun historyNumbersSurfaceAsSuggestionsWithFormattedDisplay() = runTest {
        callLog.entries.value = listOf(
            CallLogEntry(
                id = 1, number = "0407654321", displayName = null,
                type = CallType.OUTGOING, timestampMillis = 5, durationSeconds = 10,
            ),
        )
        val viewModel = viewModel()
        viewModel.setNumber("040")
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.historySuggestions.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(listOf("0407654321"), state.historySuggestions.map { it.number })
            assertEquals(
                listOf(formatDialpadNumber("0407654321", "FI")),
                state.historySuggestions.map { it.display },
            )
        }
    }

    @Test
    fun historyMatchingUsesTheSimRegionNotTheDeviceLanguage() = runTest {
        // A Finnish phone whose device language is English: the E164 round
        // trip must run in the SIM's region, or the international-format
        // history entry never matches nationally typed digits.
        callLog.entries.value = listOf(
            CallLogEntry(
                id = 1, number = "+358407654321", displayName = null,
                type = CallType.OUTGOING, timestampMillis = 5, durationSeconds = 10,
            ),
        )
        val viewModel = viewModel()
        viewModel.setNumber("040")
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.historySuggestions.isEmpty()) {
                state = awaitItem()
            }
            assertEquals(
                listOf("+358407654321"),
                state.historySuggestions.map { it.number },
            )
        }
    }

    @Test
    fun typingEchoesBeforeTheCallLogEverEmits() = runTest {
        // Returning to the dialpad restarts the upstream flows; the first
        // keystrokes must not wait for the call-log query to finish.
        val silentLog = object : FakeCallLogRepository() {
            override fun callLog(): kotlinx.coroutines.flow.Flow<List<CallLogEntry>> =
                kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }
        }
        val viewModel =
            DialpadViewModel(contacts, speedDial, silentLog, DialingRegion { "FI" }, mainDispatcherRule.dispatcher)
        viewModel.setNumber("040")
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.number != "040") {
                state = awaitItem()
            }
        }
    }

    @Test
    fun suggestionMatchingRunsOnTheInjectedBackgroundDispatcher() = runTest {
        // A multi-year call log times libphonenumber per entry; on the main
        // dispatcher every keystroke would drop dialpad frames.
        var dispatches = 0
        val counting = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                dispatches++
                mainDispatcherRule.dispatcher.dispatch(context, block)
            }
        }
        callLog.entries.value = listOf(
            CallLogEntry(
                id = 1, number = "0407654321", displayName = null,
                type = CallType.OUTGOING, timestampMillis = 5, durationSeconds = 10,
            ),
        )
        val viewModel = viewModel(matchDispatcher = counting)
        viewModel.setNumber("040")
        viewModel.uiState.test {
            var state = awaitItem()
            while (state.historySuggestions.isEmpty()) {
                state = awaitItem()
            }
        }
        assertTrue(dispatches > 0)
    }

    @Test
    fun savingASpeedDialPersistsIt() = runTest {
        val viewModel = viewModel()
        viewModel.saveSpeedDial(4, "0401234567")
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(mapOf(4 to "0401234567"), speedDial.state.value)
    }
}
