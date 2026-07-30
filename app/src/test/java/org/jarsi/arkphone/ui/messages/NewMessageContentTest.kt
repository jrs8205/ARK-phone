package org.jarsi.arkphone.ui.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NewMessageContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun contact(id: Long, name: String, number: String) = Contact(
        id = id,
        displayName = name,
        phoneNumber = number,
        photoUri = null,
        starred = false,
    )

    private fun setContent(
        selected: List<SelectedRecipient> = emptyList(),
        onToggleRecipient: (String?, String) -> Unit = { _, _ -> },
        onStart: (List<String>) -> Unit = {},
    ) {
        composeRule.setContent {
            NewMessageContent(
                uiState = NewMessageUiState(
                    loading = false,
                    contacts = listOf(contact(1, "Matti", "+358441234567")),
                    selected = selected,
                ),
                onQueryChange = {},
                onBack = {},
                onToggleRecipient = onToggleRecipient,
                onStart = onStart,
            )
        }
    }

    @Test
    fun tappingAContactTogglesItAsARecipient() {
        var toggled: String? = null
        setContent(onToggleRecipient = { _, number -> toggled = number })
        composeRule.onNodeWithText("Matti").performClick()
        assertEquals("+358441234567", toggled)
    }

    @Test
    fun selectionShowsChipsAndTheStartFabStartsWithEveryNumber() {
        var started: List<String>? = null
        setContent(
            selected = listOf(
                SelectedRecipient("Teppo", "+358400000000"),
                SelectedRecipient(null, "+358411111111"),
            ),
            onStart = { started = it },
        )
        composeRule.onNodeWithText("Teppo").assertIsDisplayed()
        composeRule.onNodeWithText("+358411111111").assertIsDisplayed()
        composeRule.onNodeWithTag("start_conversation").performClick()
        assertEquals(listOf("+358400000000", "+358411111111"), started)
    }

    @Test
    fun withoutASelectionThereIsNoStartFab() {
        setContent()
        composeRule.onNodeWithTag("start_conversation").assertDoesNotExist()
    }
}
