package org.jarsi.arkphone.ui.messages

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MessagesContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun item(
        threadId: Long = 3,
        title: String = "Matti",
        snippet: String? = "Nähdään huomenna",
        unread: Boolean = false,
    ) = ConversationItem(
        conversation = Conversation(
            threadId = threadId,
            addresses = listOf("+358441234567"),
            snippet = snippet,
            timestampMillis = 0,
            unread = unread,
        ),
        title = title,
        photoUri = null,
        isGroup = false,
    )

    @Test
    fun rowShowsTitleAndSnippetAndOpensThread() {
        var openedThread: Long? = null
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item()),
                    hasReadSmsPermission = true,
                ),
                onQueryChange = {},
                onOpenThread = { openedThread = it },
                onNewMessage = {},
                onRequestPermission = {},
            )
        }
        composeRule.onNodeWithText("Matti").assertIsDisplayed()
        composeRule.onNodeWithText("Nähdään huomenna").assertIsDisplayed()
        composeRule.onNodeWithText("Matti").performClick()
        assertEquals(3L, openedThread)
    }

    @Test
    fun unreadRowShowsTheDot() {
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item(unread = true)),
                    hasReadSmsPermission = true,
                ),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = {},
            )
        }
        composeRule.onNodeWithTag("unread", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun longPressTogglesTheRowIntoSelection() {
        var toggled: Long? = null
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item()),
                    hasReadSmsPermission = true,
                ),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = {},
                onToggleSelection = { toggled = it },
            )
        }
        composeRule.onNodeWithText("Matti").performTouchInput { longClick() }
        assertEquals(3L, toggled)
    }

    @Test
    fun selectionModeTapTogglesInsteadOfOpening() {
        var toggled: Long? = null
        var opened: Long? = null
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item()),
                    hasReadSmsPermission = true,
                    selectedThreadIds = setOf(3L),
                ),
                onQueryChange = {},
                onOpenThread = { opened = it },
                onNewMessage = {},
                onRequestPermission = {},
                onToggleSelection = { toggled = it },
            )
        }
        composeRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeRule.onNodeWithText("Matti").performClick()
        assertEquals(3L, toggled)
        assertEquals(null, opened)
    }

    @Test
    fun selectionModeHidesTheNewMessageButton() {
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item()),
                    hasReadSmsPermission = true,
                    selectedThreadIds = setOf(3L),
                ),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = {},
            )
        }
        composeRule.onNodeWithContentDescription("New message").assertDoesNotExist()
    }

    @Test
    fun selectAllActionSelectsEverything() {
        var selectedAll = false
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item(), item(threadId = 4, title = "Liisa")),
                    hasReadSmsPermission = true,
                    selectedThreadIds = setOf(3L),
                ),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = {},
                onSelectAll = { selectedAll = true },
            )
        }
        composeRule.onNodeWithContentDescription("Select all").performClick()
        assertEquals(true, selectedAll)
    }

    @Test
    fun selectAllActionOffersDeselectingWhenEverythingIsSelected() {
        var selectedAll = false
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item(), item(threadId = 4, title = "Liisa")),
                    hasReadSmsPermission = true,
                    selectedThreadIds = setOf(3L, 4L),
                ),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = {},
                onSelectAll = { selectedAll = true },
            )
        }
        composeRule.onNodeWithContentDescription("Deselect all").performClick()
        assertEquals(true, selectedAll)
    }

    @Test
    fun deleteActionConfirmsWithTheCountAndDeletes() {
        var deleted = false
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item(), item(threadId = 4, title = "Liisa")),
                    hasReadSmsPermission = true,
                    selectedThreadIds = setOf(3L, 4L),
                ),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = {},
                onDeleteSelected = { deleted = true },
            )
        }
        composeRule.onNodeWithContentDescription("Delete").performClick()
        composeRule.onNodeWithText("Delete 2 conversations?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        assertEquals(true, deleted)
    }

    @Test
    fun settingsGearOpensSettings() {
        var opened = false
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(
                    loading = false,
                    conversations = listOf(item()),
                    hasReadSmsPermission = true,
                ),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = {},
                onOpenSettings = { opened = true },
            )
        }
        composeRule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(true, opened)
    }

    @Test
    fun missingPermissionShowsTheRequestButton() {
        var requested = false
        composeRule.setContent {
            MessagesContent(
                uiState = MessagesUiState(loading = false, hasReadSmsPermission = false),
                onQueryChange = {},
                onOpenThread = {},
                onNewMessage = {},
                onRequestPermission = { requested = true },
            )
        }
        composeRule.onNodeWithText("To show your messages, allow SMS access.")
            .assertIsDisplayed()
        composeRule.onNodeWithTag("grant_sms").performClick()
        assertEquals(true, requested)
    }
}
