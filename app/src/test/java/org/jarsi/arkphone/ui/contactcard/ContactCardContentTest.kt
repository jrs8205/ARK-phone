package org.jarsi.arkphone.ui.contactcard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.LabeledField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ContactCardContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun details(starred: Boolean = false) = ContactDetails(
        id = 1,
        displayName = "Matti Meikäläinen",
        photoUri = null,
        starred = starred,
        phones = listOf(
            LabeledField("+358 44 5552841", "Mobile"),
            LabeledField("09 1234", "Home"),
        ),
        emails = listOf(LabeledField("matti@example.com", "Home")),
        addresses = listOf(LabeledField("Kotikatu 1, 00100 Helsinki", "Home")),
        events = listOf(LabeledField("1985-07-25", "Birthday")),
        organization = "Yritys Oy · Toimitusjohtaja",
        note = "Tavattu messuilla",
        websites = listOf("https://example.com"),
    )

    private fun setContent(
        details: ContactDetails?,
        onCall: (String) -> Unit = {},
        onEmail: (String) -> Unit = {},
        onOpenWebsite: (String) -> Unit = {},
        onOpenCallHistory: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            ContactCardContent(
                uiState = ContactCardUiState(loading = false, details = details),
                onBack = {},
                onCall = onCall,
                onEmail = onEmail,
                onOpenWebsite = onOpenWebsite,
                onOpenCallHistory = onOpenCallHistory,
            )
        }
    }

    @Test
    fun rendersEveryFieldGroup() {
        setContent(details())
        composeRule.onNodeWithText("Matti Meikäläinen").assertIsDisplayed()
        composeRule.onNodeWithText("+358 44 5552841").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("09 1234").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("matti@example.com").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Kotikatu 1, 00100 Helsinki").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("1985-07-25").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Yritys Oy · Toimitusjohtaja").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Tavattu messuilla").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("https://example.com").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingFieldsFiresTheirCallbacks() {
        var called: String? = null
        var emailed: String? = null
        var opened: String? = null
        setContent(
            details(),
            onCall = { called = it },
            onEmail = { emailed = it },
            onOpenWebsite = { opened = it },
        )
        composeRule.onNodeWithText("09 1234").performScrollTo().performClick()
        composeRule.onNodeWithText("matti@example.com").performScrollTo().performClick()
        composeRule.onNodeWithText("https://example.com").performScrollTo().performClick()
        assertEquals("09 1234", called)
        assertEquals("matti@example.com", emailed)
        assertEquals("https://example.com", opened)
    }

    @Test
    fun callHistoryRowUsesTheFirstNumber() {
        var historyNumber: String? = null
        setContent(details(), onOpenCallHistory = { historyNumber = it })
        composeRule.onNodeWithText("Call history").performScrollTo().performClick()
        assertEquals("+358 44 5552841", historyNumber)
    }

    @Test
    fun starIsShownOnlyForFavorites() {
        setContent(details(starred = true))
        composeRule.onNodeWithContentDescription("Favorite").assertIsDisplayed()
    }
}
