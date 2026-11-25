package org.jarsi.arkphone.ui.dialpad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakeSpeedDialRepository
import org.jarsi.arkphone.util.DialingRegion
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DialpadContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dialFieldOpensWithAnEditableCursor() {
        composeRule.setContent {
            DialpadContent(
                field = remember { TextFieldState() },
                countryIso = "FI",
                uiState = DialpadUiState(), onKey = {}, onDelete = {},
                onCall = {}, onSuggestion = {},
            )
        }
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tappingASeparatorEditsTheAdjacentRawDigitsWithoutStartingAnIme() {
        val vm = DialpadViewModel(
            FakeContactsRepository(), FakeSpeedDialRepository(), FakeCallLogRepository(),
            DialingRegion { "FI" }, Dispatchers.Default,
        )
        vm.setNumber("6502530000")
        var platformSessions = 0
        composeRule.setContent {
            InterceptPlatformTextInput(interceptor = { _, _ -> platformSessions++; awaitCancellation() }) {
                DialpadContent(
                    uiState = DialpadUiState(), field = vm.field, countryIso = "US",
                    onKey = vm::onKey, onDelete = vm::onDelete, onCall = {}, onSuggestion = {},
                )
            }
        }
        val field = composeRule.onNode(hasSetTextAction())
        val layouts = mutableListOf<TextLayoutResult>()
        field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val formatted = layout.layoutInput.text.text
        assertEquals("(650) 253-0000", formatted)
        val afterSpace = layout.getCursorRect(formatted.indexOf('2'))
        field.performTouchInput {
            val position = Offset(afterSpace.left, afterSpace.center.y)
            click(position)
            advanceEventTime(600)
            click(position)
        }
        composeRule.runOnIdle { assertEquals(TextRange(3), vm.field.selection) }
        composeRule.onNodeWithContentDescription("Delete digit").performClick()
        composeRule.runOnIdle { assertEquals("652530000", vm.field.text.toString()) }
        composeRule.onNodeWithText("8").performClick()
        composeRule.runOnIdle {
            assertEquals("6582530000", vm.field.text.toString())
            assertEquals(0, platformSessions)
        }
    }

    @Test
    fun clipboardPasteReplacesSelectionAndFiltersEveryInsertedCharacter() {
        val field = TextFieldState("12345", TextRange(1, 4))
        val visible = mutableStateOf(true)
        composeRule.setContent { if (visible.value) DialpadNumberField(field, "FI") }
        val context = ApplicationProvider.getApplicationContext<Context>()
        composeRule.runOnIdle {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("", "x +3 (*#) 4-5!"))
        }
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.PasteText)
        composeRule.runOnIdle {
            assertEquals("1+3*#455", field.text.toString())
            assertEquals(TextRange(7), field.selection)
            field.edit { selection = TextRange(2) }
            visible.value = false
        }
        composeRule.onNode(hasSetTextAction()).assertDoesNotExist()
        composeRule.runOnIdle { visible.value = true }
        composeRule.onNode(hasSetTextAction()).assertIsFocused().performSemanticsAction(SemanticsActions.PasteText)
        composeRule.runOnIdle {
            assertEquals("1++3*#453*#455", field.text.toString())
            assertEquals(TextRange(8), field.selection)
        }
    }

    @Test
    fun longPressOnDeleteClearsTheWholeNumber() {
        var cleared = false
        var deletes = 0
        composeRule.setContent {
            DialpadContent(
                field = remember { TextFieldState() },
                countryIso = "FI",
                uiState = DialpadUiState(),
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
                field = remember { TextFieldState() },
                countryIso = "FI",
                uiState = DialpadUiState(
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
                field = remember { TextFieldState() },
                countryIso = "FI",
                uiState = DialpadUiState(),
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
