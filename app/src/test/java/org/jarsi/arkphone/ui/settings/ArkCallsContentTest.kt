package org.jarsi.arkphone.ui.settings

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
class ArkCallsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun anUnregisteredDeviceOffersToCreateACode() {
        composeRule.setContent {
            ArkCallsContent(uiState = ArkCallsUiState(available = true), onBack = {})
        }
        composeRule.onNodeWithText("Create ARK code").assertIsDisplayed()
    }

    @Test
    fun aRegisteredDeviceShowsItsCode() {
        composeRule.setContent {
            ArkCallsContent(
                uiState = ArkCallsUiState(available = true, code = "ARK-7K3M-Q2FP"),
                onBack = {},
            )
        }
        composeRule.onNodeWithText("ARK-7K3M-Q2FP").assertIsDisplayed()
    }

    @Test
    fun aBuildWithoutTheEngineSaysSo() {
        composeRule.setContent {
            ArkCallsContent(uiState = ArkCallsUiState(available = false), onBack = {})
        }
        composeRule.onNodeWithText("ARK internet calls are not available in this build")
            .assertIsDisplayed()
    }

    @Test
    fun theSwitchReportsItsNewValue() {
        val changes = mutableListOf<Boolean>()
        composeRule.setContent {
            ArkCallsContent(
                uiState = ArkCallsUiState(available = true, enabled = true, code = "ARK-7K3M-Q2FP"),
                onBack = {},
                onEnabledChanged = { changes += it },
            )
        }
        composeRule.onNodeWithText("Use ARK internet calls").performClick()
        assertEquals(listOf(false), changes)
    }

    @Test
    fun aFailedRegistrationIsShown() {
        composeRule.setContent {
            ArkCallsContent(
                uiState = ArkCallsUiState(available = true, registerFailed = true),
                onBack = {},
            )
        }
        composeRule.onNodeWithText("Could not create an ARK code. Try again.").assertIsDisplayed()
    }
}
