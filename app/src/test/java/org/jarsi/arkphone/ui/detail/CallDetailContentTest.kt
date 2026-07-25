package org.jarsi.arkphone.ui.detail

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallDetailContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(id: Long, source: CallSource = CallSource.PHONE) = CallLogEntry(
        id = id,
        number = "0401234567",
        displayName = null,
        type = CallType.INCOMING,
        timestampMillis = 1_720_000_000_000 + id,
        durationSeconds = 60,
        source = source,
    )

    private fun uiState(
        blocked: Boolean = false,
        canBlock: Boolean = true,
        entries: List<CallLogEntry> = listOf(entry(1), entry(2, CallSource.WHATSAPP)),
    ) = CallDetailUiState(
        number = "0401234567",
        displayName = "Alice",
        photoUri = null,
        blocked = blocked,
        canBlock = canBlock,
        entries = entries,
        loading = false,
    )

    private fun setContent(
        uiState: CallDetailUiState,
        onCopyNumber: () -> Unit = {},
        onToggleBlocked: () -> Unit = {},
        onDeleteHistory: () -> Unit = {},
        onWhatsAppCall: () -> Unit = {},
    ) {
        composeRule.setContent {
            CallDetailContent(
                uiState = uiState,
                onBack = {},
                onCall = {},
                onMessage = {},
                onCopyNumber = onCopyNumber,
                onEditBeforeCall = {},
                onToggleBlocked = onToggleBlocked,
                onDeleteHistory = onDeleteHistory,
                onWhatsAppCall = onWhatsAppCall,
            )
        }
    }

    @Test
    fun whatsAppHistoryShowsTheWhatsAppCallButton() {
        var calls = 0
        setContent(uiState(), onWhatsAppCall = { calls++ })
        composeRule.onNodeWithContentDescription("Call on WhatsApp").performClick()
        assertEquals(1, calls)
    }

    @Test
    fun withoutWhatsAppHistoryThereIsNoWhatsAppButton() {
        setContent(uiState(entries = listOf(entry(1))))
        composeRule.onAllNodesWithContentDescription("Call on WhatsApp").assertCountEquals(0)
    }

    @Test
    fun showsHeaderStatsAndWhatsAppLabel() {
        setContent(uiState())
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Statistics").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Talk time").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2 min 0 s").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Incoming call · WhatsApp").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun menuOffersActionsAndCopyReports() {
        var copied = false
        setContent(uiState(), onCopyNumber = { copied = true })
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Edit number before call").assertIsDisplayed()
        composeRule.onNodeWithText("Block number").assertIsDisplayed()
        composeRule.onNodeWithText("Copy number").performClick()
        assertTrue(copied)
    }

    @Test
    fun blockedStateShowsBadgeAndUnblockItem() {
        setContent(uiState(blocked = true))
        composeRule.onNodeWithText("Blocked").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Unblock number").assertIsDisplayed()
    }

    @Test
    fun deleteAsksForConfirmationBeforeReporting() {
        var deletes = 0
        setContent(uiState(), onDeleteHistory = { deletes++ })
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Delete history").performClick()
        assertEquals(0, deletes)
        composeRule.onNodeWithText("Delete call history?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(1, deletes)
    }
}
