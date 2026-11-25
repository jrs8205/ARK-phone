package org.jarsi.arkphone.ui.incall

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.telecom.CallInfo
import org.jarsi.arkphone.telecom.CallStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun openingKeypadShowsDigitRow() {
        composeRule.setContent {
            CallScreen(
                uiState = InCallUiState(call = ringingCall("Caller").copy(status = CallStatus.ACTIVE), showKeypad = true),
                actions = noOpActions,
            )
        }
        composeRule.onNodeWithTag("dtmfDigits").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w320dp-h568dp")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun keypadReopensAtNewestDigitsAndLeavesRoomForCallControls() {
        val digits = "1234567890*#".repeat(12)
        val state = mutableStateOf(InCallUiState(
            call = ringingCall("Caller").copy(status = CallStatus.ACTIVE), dtmfDigits = digits,
        ))
        val actions = object : InCallActions by noOpActions {
            override fun onToggleKeypad() { state.value = state.value.copy(showKeypad = !state.value.showKeypad) }
        }
        composeRule.setContent { CallScreen(state.value, actions) }
        val row = composeRule.onNodeWithTag("dtmfDigits")
        val toggle = composeRule.onNodeWithContentDescription("Keypad")
        row.assertDoesNotExist()
        repeat(2) {
            toggle.performClick()
            row.assertIsDisplayed()
            composeRule.onNodeWithText(digits).assertExists()
            val range = row.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            org.junit.Assert.assertTrue("expected horizontal overflow: ${range.maxValue()}", range.maxValue() > 0)
            org.junit.Assert.assertTrue("expected newest digits at scroll origin", range.reverseScrolling)
            assertEquals(0f, range.value())
            composeRule.onNodeWithText("Caller").assertIsDisplayed()
            composeRule.onNodeWithText("#").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("End call").assertIsDisplayed()
            toggle.performClick()
            row.assertDoesNotExist()
        }
    }

    private val noOpActions = object : InCallActions {
        override fun onAnswer() {}
        override fun onReject() {}
        override fun onHangUp() {}
        override fun onToggleMute() {}
        override fun onToggleSpeaker() {}
        override fun onToggleHold() {}
        override fun onToggleKeypad() {}
        override fun onDtmf(digit: Char) {}
        override fun onSilence() {}
        override fun onRejectWithMessage(message: String) {}
    }

    @Test
    fun unknownCallerShowsTheWarningTag() {
        composeRule.setContent {
            CallScreen(
                uiState = InCallUiState(call = ringingCall(null), knownCaller = false),
                actions = noOpActions,
            )
        }
        composeRule.onNodeWithText("Unknown number").assertExists()
    }

    @Test
    fun silenceButtonSilences() {
        var silenced = 0
        val actions = object : InCallActions by noOpActions {
            override fun onSilence() {
                silenced++
            }
        }
        composeRule.setContent {
            CallScreen(
                uiState = InCallUiState(call = ringingCall("Matti")),
                actions = actions,
            )
        }
        composeRule.onNodeWithText("Silence").performClick()
        assertEquals(1, silenced)
    }

    @Test
    fun rejectWithMessagePicksACannedReply() {
        var message: String? = null
        val actions = object : InCallActions by noOpActions {
            override fun onRejectWithMessage(value: String) {
                message = value
            }
        }
        composeRule.setContent {
            CallScreen(
                uiState = InCallUiState(call = ringingCall("Matti")),
                actions = actions,
            )
        }
        composeRule.onNodeWithText("Message").performClick()
        composeRule.onNodeWithText("I'll call you soon.").performClick()
        assertEquals("I'll call you soon.", message)
    }

    private fun ringingCall(name: String?) =
        CallInfo("call-1", "0401234567", name, CallStatus.RINGING, null)

    @Test
    fun namedCallerGetsALetterAvatar() {
        composeRule.setContent {
            CallScreen(
                uiState = InCallUiState(call = ringingCall("Matti")),
                actions = noOpActions,
            )
        }
        composeRule.onNodeWithTag("callerAvatar").assertIsDisplayed()
        composeRule.onNodeWithText("M").assertIsDisplayed()
    }

    @Test
    fun unknownCallerStillShowsAnAvatar() {
        composeRule.setContent {
            CallScreen(
                uiState = InCallUiState(call = ringingCall(name = null)),
                actions = noOpActions,
            )
        }
        composeRule.onNodeWithTag("callerAvatar").assertIsDisplayed()
    }
}
