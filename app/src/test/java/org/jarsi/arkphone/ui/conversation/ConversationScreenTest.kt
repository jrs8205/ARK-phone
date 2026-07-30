package org.jarsi.arkphone.ui.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.data.model.MmsAttachment
import org.jarsi.arkphone.messaging.MessagingSim
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
        sims: List<MessagingSim> = emptyList(),
        selectedSubscriptionId: Int = -1,
        onSendText: (String) -> Unit = {},
        onCycleSim: () -> Unit = {},
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
                    sims = sims,
                    selectedSubscriptionId = selectedSubscriptionId,
                    attachedImageUri = attachedImageUri,
                ),
                onBack = {},
                onCall = {},
                onOpenContact = {},
                onToggleBlocked = {},
                onDeleteConversation = {},
                onSendText = onSendText,
                onCycleSim = onCycleSim,
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
    fun composerIsDisabledForGroupThreads() {
        setContent(listOf(message(1, 1_000)), address = null)
        composeRule.onNodeWithTag("composer_input").assertIsNotEnabled()
    }

    @Test
    fun simChipShowsTheSelectedSimOnlyWithSeveralSims() {
        var cycled = false
        setContent(
            listOf(message(1, 1_000)),
            sims = listOf(MessagingSim(1, "DNA"), MessagingSim(2, "Elisa")),
            selectedSubscriptionId = 2,
            onCycleSim = { cycled = true },
        )
        composeRule.onNodeWithTag("sim_chip").assertIsDisplayed()
        composeRule.onNodeWithText("Elisa").assertIsDisplayed()
        composeRule.onNodeWithTag("sim_chip").performClick()
        assertEquals(true, cycled)
    }

    @Test
    fun simChipIsHiddenOnASingleSimDevice() {
        setContent(
            listOf(message(1, 1_000)),
            sims = listOf(MessagingSim(1, "DNA")),
            selectedSubscriptionId = 1,
        )
        composeRule.onNodeWithTag("sim_chip").assertDoesNotExist()
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
