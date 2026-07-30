package org.jarsi.arkphone.ui.navigation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

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
