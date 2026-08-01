package org.jarsi.arkphone.ui.conversation

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.data.model.MmsAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        onRetryDownload: (Long) -> Unit = {},
        attachedImageUri: String? = null,
        onRemoveAttachment: () -> Unit = {},
        selectedKeys: Set<MessageKey> = emptySet(),
        onToggleMessageSelection: (Message) -> Unit = {},
        onDeleteSelected: () -> Unit = {},
        onShareSelected: () -> Unit = {},
        onRetry: (Message) -> Unit = {},
        onCallNumber: (String) -> Unit = {},
        onOpenLink: (String) -> Unit = {},
        initialComposerText: String = "",
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
                    selectedMessageKeys = selectedKeys,
                ),
                onBack = {},
                onCall = {},
                onOpenContact = {},
                onToggleBlocked = {},
                onDeleteConversation = {},
                onSendText = onSendText,
                onRetry = onRetry,
                onRetryDownload = onRetryDownload,
                onRemoveAttachment = onRemoveAttachment,
                onToggleMessageSelection = onToggleMessageSelection,
                onDeleteSelected = onDeleteSelected,
                onShareSelected = onShareSelected,
                onCallNumber = onCallNumber,
                onOpenLink = onOpenLink,
                initialComposerText = initialComposerText,
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
    fun longPressOnABubbleTogglesItIntoSelection() {
        var toggled: Message? = null
        setContent(listOf(message(1, 1_000)), onToggleMessageSelection = { toggled = it })
        composeRule.onNodeWithTag("bubble_in", useUnmergedTree = true)
            .performTouchInput { longClick() }
        assertEquals(1L, toggled?.id)
    }

    @Test
    fun selectionModeTapTogglesInsteadOfRetrying() {
        var toggled: Message? = null
        var retried = false
        val failed = message(1, 1_000, incoming = false, status = MessageStatus.FAILED)
        setContent(
            listOf(failed),
            selectedKeys = setOf(MessageKey(9, false)),
            onToggleMessageSelection = { toggled = it },
            onRetry = { retried = true },
        )
        composeRule.onNodeWithTag("bubble_out", useUnmergedTree = true).performClick()
        assertEquals(1L, toggled?.id)
        assertEquals(false, retried)
    }

    @Test
    fun selectionDeleteConfirmsWithTheCountAndDeletes() {
        var deleted = false
        setContent(
            listOf(message(1, 1_000), message(2, 2_000)),
            selectedKeys = setOf(MessageKey(1, false), MessageKey(2, false)),
            onDeleteSelected = { deleted = true },
        )
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNodeWithText("Delete 2 messages?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(true, deleted)
    }

    @Test
    fun sharedTextPrefillsTheComposer() {
        var sent: String? = null
        setContent(
            listOf(message(1, 1_000)),
            onSendText = { sent = it },
            initialComposerText = "Jaettu teksti",
        )
        composeRule.onNodeWithTag("composer_input").assertTextContains("Jaettu teksti")
        composeRule.onNodeWithTag("composer_send").performClick()
        assertEquals("Jaettu teksti", sent)
    }

    @Test
    fun selectionShareForwardsToTheShareAction() {
        var shared = false
        setContent(
            listOf(message(1, 1_000)),
            selectedKeys = setOf(MessageKey(1, false)),
            onShareSelected = { shared = true },
        )
        composeRule.onNodeWithContentDescription("Share").performClick()
        assertEquals(true, shared)
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

    // Robolectric's glyph boxes are reliable only for the first character of a style
    // run, so always target a run-initial index: a link's start, or 0 for plain text.
    private fun SemanticsNodeInteraction.tapAtChar(index: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val center = layouts.first().getBoundingBox(index).center
        performTouchInput { click(center) }
    }

    private fun SemanticsNodeInteraction.longPressAtChar(index: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val center = layouts.first().getBoundingBox(index).center
        performTouchInput { longClick(center) }
    }

    @Test
    fun tappingALinkOpensIt() {
        var opened: String? = null
        setContent(
            listOf(message(1, 1_000, body = "Katso https://yle.fi nyt")),
            onOpenLink = { opened = it },
        )
        composeRule.onNodeWithText("Katso https://yle.fi nyt", useUnmergedTree = true)
            .tapAtChar(6)
        assertEquals("https://yle.fi", opened)
    }

    @Test
    fun aLinkTapLosesToActiveSelectionMode() {
        var opened: String? = null
        var toggled: Message? = null
        setContent(
            listOf(message(1, 1_000, body = "Katso https://yle.fi nyt")),
            selectedKeys = setOf(MessageKey(99, false)),
            onToggleMessageSelection = { toggled = it },
            onOpenLink = { opened = it },
        )
        composeRule.onNodeWithText("Katso https://yle.fi nyt", useUnmergedTree = true)
            .tapAtChar(6)
        assertNull(opened)
        assertEquals(1L, toggled?.id)
    }

    @Test
    fun longPressOnALinkStartsSelection() {
        var opened: String? = null
        var toggled: Message? = null
        setContent(
            listOf(message(1, 1_000, body = "Katso https://yle.fi nyt")),
            onToggleMessageSelection = { toggled = it },
            onOpenLink = { opened = it },
        )
        composeRule.onNodeWithText("Katso https://yle.fi nyt", useUnmergedTree = true)
            .longPressAtChar(6)
        assertNull(opened)
        assertEquals(1L, toggled?.id)
    }

    @Test
    fun aFailedSmsRetriesFromPlainTextButOpensItsLink() {
        var retried = false
        var opened: String? = null
        setContent(
            listOf(
                message(
                    2,
                    2_000,
                    incoming = false,
                    body = "uusi yritys https://yle.fi",
                    status = MessageStatus.FAILED,
                ),
            ),
            onRetry = { retried = true },
            onOpenLink = { opened = it },
        )
        val bubbleText =
            composeRule.onNodeWithText("uusi yritys https://yle.fi", useUnmergedTree = true)
        bubbleText.tapAtChar(0)
        assertTrue(retried)
        assertNull(opened)
        bubbleText.tapAtChar(12)
        assertEquals("https://yle.fi", opened)
    }

    @Test
    fun tappingAPhoneNumberOffersCallCopyCancel() {
        var called: String? = null
        setContent(
            listOf(message(1, 1_000, body = "soita 040 1234567")),
            onCallNumber = { called = it },
        )
        composeRule.onNodeWithText("soita 040 1234567", useUnmergedTree = true).tapAtChar(6)
        composeRule.onNodeWithText("0401234567").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
        composeRule.onNodeWithText("Call").performClick()
        assertEquals("0401234567", called)
        composeRule.onNodeWithText("0401234567").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun linksAreExposedAsAccessibilityActions() {
        var opened: String? = null
        setContent(
            listOf(message(1, 1_000, body = "Katso https://yle.fi nyt")),
            onOpenLink = { opened = it },
        )
        composeRule.onNodeWithText("Katso https://yle.fi nyt", useUnmergedTree = true)
            .performCustomAccessibilityActionWithLabel("https://yle.fi")
        assertEquals("https://yle.fi", opened)
    }

    @Test
    fun tappingTheCheckboxTogglesTheMessage() {
        var toggled: Message? = null
        setContent(
            listOf(message(1, 1_000), message(2, 2_000)),
            selectedKeys = setOf(MessageKey(1, false)),
            onToggleMessageSelection = { toggled = it },
        )
        composeRule.onAllNodesWithTag("select_message")[1].performClick()
        assertEquals(2L, toggled?.id)
        composeRule.onAllNodesWithTag("select_message")[0].performClick()
        assertEquals(1L, toggled?.id)
    }

    @Test
    fun selectionSurvivesTheFullLongPressFlow() {
        val selected = mutableStateOf(setOf<MessageKey>())
        composeRule.setContent {
            ConversationContent(
                uiState = ConversationUiState(
                    loading = false,
                    messages = listOf(
                        message(1, 1_000, body = "Katso https://yle.fi nyt"),
                        message(2, 2_000, body = "Moro vaan"),
                    ),
                    title = "Matti",
                    address = "+358441234567",
                    selectedMessageKeys = selected.value,
                ),
                onBack = {},
                onCall = {},
                onOpenContact = {},
                onToggleBlocked = {},
                onDeleteConversation = {},
                onToggleMessageSelection = { m ->
                    val k = m.selectionKey
                    selected.value =
                        if (k in selected.value) selected.value - k else selected.value + k
                },
            )
        }
        composeRule.onNodeWithText("Katso https://yle.fi nyt", useUnmergedTree = true)
            .longPressAtChar(6)
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Moro vaan", useUnmergedTree = true)
            .performTouchInput { longClick() }
        composeRule.onNodeWithText("2 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Moro vaan", useUnmergedTree = true).performClick()
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Katso https://yle.fi nyt", useUnmergedTree = true)
            .tapAtChar(6)
        composeRule.onNodeWithText("1 selected").assertDoesNotExist()
    }

    @Test
    fun copyingFromTheNumberDialogFillsTheClipboard() {
        setContent(listOf(message(1, 1_000, body = "soita 040 1234567")))
        composeRule.onNodeWithText("soita 040 1234567", useUnmergedTree = true).tapAtChar(6)
        composeRule.onNodeWithText("Copy").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(ClipboardManager::class.java)
        assertEquals("0401234567", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        composeRule.onNodeWithText("Copy").assertDoesNotExist()
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
