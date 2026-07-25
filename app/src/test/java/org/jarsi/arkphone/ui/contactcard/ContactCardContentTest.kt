package org.jarsi.arkphone.ui.contactcard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.ContactAppAction
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
        appActions = listOf(
            ContactAppAction(41, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call", "Voice call +358 44 5552841"),
            ContactAppAction(42, "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact", "Message +358 44 5552841"),
        ),
        lookupKey = "lookup-1",
    )

    private fun setContent(
        details: ContactDetails?,
        blocked: Boolean = false,
        canBlock: Boolean = true,
        onCall: (String) -> Unit = {},
        onMessage: (String) -> Unit = {},
        onEmail: (String) -> Unit = {},
        onOpenAddress: (String) -> Unit = {},
        onOpenWebsite: (String) -> Unit = {},
        onOpenCallHistory: (String) -> Unit = {},
        onOpenAppAction: (ContactAppAction) -> Unit = {},
        onShare: () -> Unit = {},
        onToggleBlocked: () -> Unit = {},
    ) {
        composeRule.setContent {
            ContactCardContent(
                uiState = ContactCardUiState(
                    loading = false,
                    details = details,
                    blocked = blocked,
                    canBlock = canBlock,
                ),
                onBack = {},
                onCall = onCall,
                onMessage = onMessage,
                onEmail = onEmail,
                onOpenAddress = onOpenAddress,
                onOpenWebsite = onOpenWebsite,
                onOpenCallHistory = onOpenCallHistory,
                onOpenAppAction = onOpenAppAction,
                onShare = onShare,
                onToggleBlocked = onToggleBlocked,
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

    @Test
    fun connectedAppActionsAreListedAndTappable() {
        var opened: ContactAppAction? = null
        setContent(details(), onOpenAppAction = { opened = it })
        composeRule.onNodeWithText("Connected apps").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Message +358 44 5552841").performScrollTo().performClick()
        assertEquals(42L, opened?.dataId)
    }

    @Test
    fun shareRowFiresItsCallback() {
        var shared = false
        setContent(details(), onShare = { shared = true })
        composeRule.onNodeWithText("Share contact").performScrollTo().performClick()
        assertEquals(true, shared)
    }

    @Test
    fun blockRowTogglesAndReflectsTheState() {
        var toggles = 0
        setContent(details(), blocked = false, onToggleBlocked = { toggles++ })
        composeRule.onNodeWithText("Block numbers").performScrollTo().performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun blockedStateShowsTheUnblockRow() {
        setContent(details(), blocked = true)
        composeRule.onNodeWithText("Unblock numbers").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun phoneRowMessageIconMessagesThatNumber() {
        var messaged: String? = null
        setContent(details(), onMessage = { messaged = it })
        composeRule.onAllNodesWithContentDescription("Message")[1].performScrollTo().performClick()
        assertEquals("09 1234", messaged)
    }

    @Test
    fun addressDirectionsIconOpensTheMap() {
        var opened: String? = null
        setContent(details(), onOpenAddress = { opened = it })
        composeRule.onNodeWithContentDescription("Directions").performScrollTo().performClick()
        assertEquals("Kotikatu 1, 00100 Helsinki", opened)
    }
}
