package org.jarsi.arkphone.ui.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ConversationScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun message(
        id: Long,
        timestamp: Long,
        incoming: Boolean = true,
        body: String = "Moro",
        status: MessageStatus = MessageStatus.NONE,
    ) = Message(
        id = id,
        threadId = 3,
        isMms = false,
        address = "+358441234567",
        body = body,
        timestampMillis = timestamp,
        incoming = incoming,
        status = status,
        subscriptionId = 1,
    )

    private fun setContent(messages: List<Message>) {
        composeRule.setContent {
            ConversationContent(
                uiState = ConversationUiState(
                    loading = false,
                    messages = messages,
                    title = "Matti",
                    address = "+358441234567",
                ),
                onBack = {},
                onCall = {},
                onOpenContact = {},
                onToggleBlocked = {},
                onDeleteConversation = {},
            )
        }
    }

    @Test
    fun incomingAndOutgoingBubblesGetTheirOwnTags() {
        setContent(
            listOf(
                message(1, 1_000, incoming = true),
                message(2, 2_000, incoming = false, status = MessageStatus.SENT),
            ),
        )
        composeRule.onNodeWithTag("bubble_in", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag("bubble_out", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun failedOutgoingShowsTheRetryHint() {
        setContent(listOf(message(2, 2_000, incoming = false, status = MessageStatus.FAILED)))
        composeRule.onNodeWithText("Failed — tap to retry").assertIsDisplayed()
    }

    @Test
    fun eachDayGetsItsSeparator() {
        val dayOne = 1_000_000_000_000L
        setContent(
            listOf(
                message(1, dayOne),
                message(2, dayOne + 48 * 60 * 60 * 1000L),
            ),
        )
        composeRule.onAllNodesWithTag("day_separator").assertCountEquals(2)
    }

    @Test
    fun deliveredStatusShowsUnderTheNewestOutgoingMessage() {
        setContent(
            listOf(
                message(1, 1_000, incoming = false, status = MessageStatus.SENT),
                message(2, 2_000, incoming = false, status = MessageStatus.DELIVERED),
            ),
        )
        composeRule.onNodeWithText("Delivered").assertIsDisplayed()
    }
}
