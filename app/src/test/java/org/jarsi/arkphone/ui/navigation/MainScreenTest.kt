package org.jarsi.arkphone.ui.navigation

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.telecom.CallInfo
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.ui.incall.InCallActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun returnBarTracksLiveCallsAndOpensTheirTask() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val ended = CallInfo("old", "111", null, CallStatus.DISCONNECTED, null)
        val live = CallInfo("new", "222", "Ada", CallStatus.RINGING, null)
        val calls = mutableStateOf(listOf(ended))
        composeRule.setContent { ReturnToCallBar(calls.value) }
        composeRule.onNodeWithText("Return to call", substring = true).assertDoesNotExist()
        composeRule.runOnIdle { calls.value = listOf(ended, live) }
        composeRule.onNodeWithText("Return to call", substring = true).performClick()
        composeRule.onNodeWithText("Ada", substring = true).assertExists()
        assertEquals(InCallActivity.intent(context).component, shadowOf(context).nextStartedActivity.component)
        composeRule.runOnIdle { calls.value = listOf(live.copy(displayName = null)) }
        composeRule.onNodeWithText("222", substring = true).assertExists()
        composeRule.runOnIdle { calls.value = listOf(live.copy(status = CallStatus.DISCONNECTED)) }
        composeRule.onNodeWithText("Return to call", substring = true).assertDoesNotExist()
    }

    @Test
    fun theMessagesTabShowsTheUnreadBadge() {
        composeRule.setContent {
            ArkBottomBar(selected = MainTab.HOME, onSelect = {}, unreadMessages = 3)
        }
        composeRule.onNodeWithText("3", useUnmergedTree = true).assertExists()
    }

    @Test
    fun noBadgeWithoutUnreadConversations() {
        composeRule.setContent {
            ArkBottomBar(selected = MainTab.HOME, onSelect = {}, unreadMessages = 0)
        }
        composeRule.onNodeWithText("0", useUnmergedTree = true).assertDoesNotExist()
    }
}
