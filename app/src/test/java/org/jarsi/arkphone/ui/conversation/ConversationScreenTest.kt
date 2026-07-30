package org.jarsi.arkphone.ui.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.data.model.MmsAttachment
import org.junit.Assert.assertEquals
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

    private fun setContent(
        messages: List<Message>,
        address: String? = "+358441234567",
        isGroup: Boolean = false,
        onSendText: (String) -> Unit = {},
        onDeleteMessage: (Message) -> Unit = {},
        onRetryDownload: (Long) -> Unit = {},
        attachedImageUri: String? = null,
        onRemoveAttachment: () -> Unit = {},
    ) {
        composeRule.setContent {
            ConversationContent(
                uiState = ConversationUiState(
                    loading = false,
                    messages = messages,
                    title = "Matti",
                    address = address,
                    isGroup = isGroup,
                    attachedImageUri = attachedImageUri,
                ),
                onBack = {},
                onCall = {},
                onOpenContact = {},
                onToggleBlocked = {},
                onDeleteConversation = {},
                onSendText = onSendText,
                onDeleteMessage = onDeleteMessage,
                onRetryDownload = onRetryDownload,
                onRemoveAttachment = onRemoveAttachment,
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
    fun aFailedMmsShowsPlainFailureWithoutARetryPromise() {
        // The retry path only exists for SMS; the label must not promise a
        // tap that does nothing.
        setContent(
            listOf(
                message(2, 2_000, incoming = false, status = MessageStatus.FAILED)
                    .copy(isMms = true),
            ),
        )
        composeRule.onNodeWithText("Failed").assertIsDisplayed()
        composeRule.onNodeWithText("Failed — tap to retry").assertDoesNotExist()
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
    fun typedTextIsSentAndTheFieldCleared() {
        var sent: String? = null
        setContent(listOf(message(1, 1_000)), onSendText = { sent = it })
        composeRule.onNodeWithTag("composer_input").performTextInput("Moro taas")
        composeRule.onNodeWithTag("composer_send").performClick()
        assertEquals("Moro taas", sent)
        composeRule.onNodeWithTag("composer_input").assertTextContains("")
    }

    @Test
    fun sendIsDisabledWhileTheFieldIsBlank() {
        setContent(listOf(message(1, 1_000)))
        composeRule.onNodeWithTag("composer_send").assertIsNotEnabled()
    }

    @Test
    fun longPressOnABubbleOffersDeleteAndConfirmingDeletes() {
        var deleted: Message? = null
        setContent(listOf(message(1, 1_000)), onDeleteMessage = { deleted = it })
        composeRule.onNodeWithTag("bubble_in", useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("Delete message?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(1L, deleted?.id)
    }

    @Test
    fun menuOffersCopyNumberForSingleConversations() {
        setContent(listOf(message(1, 1_000)))
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Copy number").assertIsDisplayed()
    }

    @Test
    fun copyNumberIsAbsentWithoutAnAddress() {
        setContent(listOf(message(1, 1_000)), address = null, isGroup = true)
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Copy number").assertDoesNotExist()
    }

    @Test
    fun composerIsDisabledWhenTheOtherPartyIsUnknown() {
        setContent(listOf(message(1, 1_000)), address = null)
        composeRule.onNodeWithTag("composer_input").assertIsNotEnabled()
    }

    @Test
    fun composerIsEnabledForGroupThreads() {
        setContent(listOf(message(1, 1_000)), address = null, isGroup = true)
        composeRule.onNodeWithTag("composer_input").assertIsEnabled()
    }

    @Test
    fun anMmsImageRendersInsideTheBubble() {
        setContent(
            listOf(
                message(1, 1_000).copy(
                    isMms = true,
                    attachments = listOf(MmsAttachment("content://mms/part/901", "image/jpeg")),
                ),
            ),
        )
        composeRule.onNodeWithTag("mms_image", useUnmergedTree = true).assertExists()
    }

    @Test
    fun aPendingDownloadRowFiresTheRetryCallback() {
        var retried: Long? = null
        setContent(
            listOf(message(9, 1_000).copy(isMms = true, pendingDownload = true, body = null)),
            onRetryDownload = { retried = it },
        )
        composeRule.onNodeWithText("Download failed — tap to retry").performClick()
        assertEquals(9L, retried)
    }

    @Test
    fun anAttachmentPreviewShowsAndCanBeRemoved() {
        var removed = false
        setContent(
            listOf(message(1, 1_000)),
            attachedImageUri = "content://media/1",
            onRemoveAttachment = { removed = true },
        )
        composeRule.onNodeWithTag("attachment_preview", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("attachment_remove").performClick()
        assertEquals(true, removed)
    }

    @Test
    fun anAttachmentAloneEnablesSend() {
        setContent(listOf(message(1, 1_000)), attachedImageUri = "content://media/1")
        composeRule.onNodeWithTag("composer_send").assertIsEnabled()
    }

    @Test
    fun everyOutgoingMessageKeepsItsStatusVisible() {
        // The provider keeps TYPE/STATUS per row, so the label must not be
        // limited to the newest message: delivery evidence stays readable
        // for old messages too.
        setContent(
            listOf(
                message(1, 1_000, incoming = false, status = MessageStatus.SENT),
                message(2, 2_000, incoming = false, status = MessageStatus.DELIVERED),
            ),
        )
        composeRule.onNodeWithText("Sent").assertIsDisplayed()
        composeRule.onNodeWithText("Delivered").assertIsDisplayed()
    }
}
