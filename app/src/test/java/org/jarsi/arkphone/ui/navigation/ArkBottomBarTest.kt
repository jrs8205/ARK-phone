package org.jarsi.arkphone.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArkBottomBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsAllThreeTabsAndReportsSelection() {
        var selected = MainTab.HOME
        composeRule.setContent {
            ArkBottomBar(selected = selected, onSelect = { selected = it })
        }
        composeRule.onNodeWithText("Home").assertIsDisplayed()
        composeRule.onNodeWithText("Keypad").assertIsDisplayed()
        composeRule.onNodeWithText("Contacts").assertIsDisplayed()
        composeRule.onNodeWithText("Keypad").performClick()
        assertEquals(MainTab.KEYPAD, selected)
    }

    @Test
    fun contactsTabIsSelectable() {
        var selected = MainTab.HOME
        composeRule.setContent {
            ArkBottomBar(selected = selected, onSelect = { selected = it })
        }
        composeRule.onNodeWithText("Contacts").performClick()
        assertEquals(MainTab.CONTACTS, selected)
    }
}
