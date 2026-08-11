package org.jarsi.arkphone.ui.dialpad

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
    fun longPressOnDeleteClearsTheWholeNumber() {
        var cleared = false
        var deletes = 0
        composeRule.setContent {
            DialpadContent(
                uiState = DialpadUiState(number = "0401234567"),
                onKey = {},
                onDelete = { deletes++ },
                onCall = {},
                onSuggestion = {},
                onClear = { cleared = true },
            )
        }
        composeRule.onNodeWithContentDescription("Delete digit")
            .performTouchInput { longClick() }
        assertEquals(true, cleared)
        assertEquals(0, deletes)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w400dp-h1000dp")
    fun tappingAHistorySuggestionFillsTheNumber() {
        var suggested: String? = null
        composeRule.setContent {
            DialpadContent(
                uiState = DialpadUiState(
                    number = "040",
                    historySuggestions = listOf(
                        HistorySuggestion(number = "0401234567", display = "040 123 4567"),
                    ),
                ),
                onKey = {},
                onDelete = {},
                onCall = {},
                onSuggestion = { suggested = it },
            )
        }
        composeRule.onNodeWithText("040 123 4567").performClick()
        assertEquals("0401234567", suggested)
    }

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
