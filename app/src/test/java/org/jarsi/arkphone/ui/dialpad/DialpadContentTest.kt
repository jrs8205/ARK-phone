package org.jarsi.arkphone.ui.dialpad

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
class DialpadContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun keysReportPresses() {
        val pressed = mutableListOf<Char>()
        composeRule.setContent {
            DialpadContent(
                uiState = DialpadUiState(number = ""),
                onKey = { pressed.add(it) },
                onDelete = {},
                onCall = {},
                onSuggestion = {},
            )
        }
        composeRule.onNodeWithText("2").performClick()
        composeRule.onNodeWithText("5").performClick()
        assertEquals(listOf('2', '5'), pressed)
    }
}
