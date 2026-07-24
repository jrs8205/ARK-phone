package org.jarsi.arkphone.ui.incall

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.telecom.CallInfo
import org.jarsi.arkphone.telecom.CallStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val noOpActions = object : InCallActions {
        override fun onAnswer() {}
        override fun onReject() {}
        override fun onHangUp() {}
        override fun onToggleMute() {}
        override fun onToggleSpeaker() {}
        override fun onToggleHold() {}
        override fun onToggleKeypad() {}
        override fun onDtmf(digit: Char) {}
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
