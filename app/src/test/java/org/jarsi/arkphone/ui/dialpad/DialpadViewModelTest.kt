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
import org.junit.Assert.assertEquals
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

    private fun viewModel() = DialpadViewModel(contacts, speedDial, callLog)

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
    fun displayNumberIsFormattedForTheDefaultCountry() = runTest {
        val viewModel = viewModel()
        viewModel.setNumber("0401234567")
        viewModel.uiState.test {
            skipItems(1)
            val state = awaitItem()
            assertEquals(
                formatDialpadNumber("0401234567", java.util.Locale.getDefault().country),
                state.displayNumber,
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
            skipItems(1)
            val state = awaitItem()
            assertEquals(listOf("0407654321"), state.historySuggestions.map { it.number })
            assertEquals(
                listOf(formatDialpadNumber("0407654321", java.util.Locale.getDefault().country)),
                state.historySuggestions.map { it.display },
            )
        }
    }

    @Test
    fun savingASpeedDialPersistsIt() = runTest {
        val viewModel = viewModel()
        viewModel.saveSpeedDial(4, "0401234567")
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(mapOf(4 to "0401234567"), speedDial.state.value)
    }
}
