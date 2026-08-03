package org.jarsi.arkphone.ui.incall

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.telecom.DisconnectError
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DisconnectErrorDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsThePlatformTextsAndDismisses() {
        var dismissed = false
        composeRule.setContent {
            DisconnectErrorDialog(
                error = DisconnectError(
                    label = "Radio pois käytöstä",
                    description = "Poista lentokonetila käytöstä.",
                ),
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Radio pois käytöstä").assertIsDisplayed()
        composeRule.onNodeWithText("Poista lentokonetila käytöstä.").assertIsDisplayed()
        composeRule.onNodeWithText("OK").performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun fallsBackToAGenericTitleWithoutALabel() {
        composeRule.setContent {
            DisconnectErrorDialog(
                error = DisconnectError(label = null, description = "Verkkovirhe."),
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Call failed").assertIsDisplayed()
        composeRule.onNodeWithText("Verkkovirhe.").assertIsDisplayed()
    }
}
