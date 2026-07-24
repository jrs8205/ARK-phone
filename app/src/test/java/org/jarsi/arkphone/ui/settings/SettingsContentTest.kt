package org.jarsi.arkphone.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun announceCallerSwitchReflectsAndWritesState() {
        val changes = mutableListOf<Boolean>()
        composeRule.setContent {
            SettingsContent(
                settings = Settings(announceCaller = false),
                onAnnounceCallerChanged = { changes.add(it) },
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Spoken caller announcement").assertIsDisplayed()
        composeRule.onNode(isToggleable()).assertIsOff()
        composeRule.onNode(isToggleable()).performClick()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun enabledSettingShowsSwitchOn() {
        composeRule.setContent {
            SettingsContent(
                settings = Settings(announceCaller = true),
                onAnnounceCallerChanged = {},
                onBack = {},
            )
        }
        composeRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun backButtonInvokesCallback() {
        var backed = false
        composeRule.setContent {
            SettingsContent(
                settings = Settings(),
                onAnnounceCallerChanged = {},
                onBack = { backed = true },
            )
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }
}
