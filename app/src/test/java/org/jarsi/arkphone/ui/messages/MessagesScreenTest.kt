package org.jarsi.arkphone.ui.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MessagesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        isDefaultSmsApp: Boolean,
        onRequestSmsRole: () -> Unit = {},
    ) {
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    isDefaultSmsApp = isDefaultSmsApp,
                ),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = {},
                onRequestSmsRole = onRequestSmsRole,
            )
        }
    }

    @Test
    fun theRoleBannerShowsWhenTheAppIsNotTheSmsApp() {
        var requested = false
        setContent(isDefaultSmsApp = false, onRequestSmsRole = { requested = true })
        composeRule.onNodeWithText("ARK-phone is not your SMS app").assertIsDisplayed()
        composeRule.onNodeWithText("Set as default").performClick()
        assertTrue(requested)
    }

    @Test
    fun noBannerWhenTheRoleIsHeld() {
        setContent(isDefaultSmsApp = true)
        composeRule.onNodeWithText("ARK-phone is not your SMS app").assertDoesNotExist()
    }
}
