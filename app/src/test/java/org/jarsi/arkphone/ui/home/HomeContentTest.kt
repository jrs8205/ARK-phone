package org.jarsi.arkphone.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.ui.recents.GroupedCallLogEntry
import org.jarsi.arkphone.ui.recents.RecentsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HomeContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun contact(id: Long, name: String) = Contact(
        id = id,
        displayName = name,
        phoneNumber = "040123456$id",
        photoUri = null,
        starred = true,
    )

    @Test
    fun searchFieldAndFilterChipsSitOnTop() {
        var query: String? = null
        composeRule.setContent {
            HomeContent(
                favorites = emptyList(),
                recentsUiState = RecentsUiState(loading = false),
                onCall = {},
                onRequestPermissions = {},
                onQueryChange = { query = it },
            )
        }
        composeRule.onNodeWithText("Search calls").assertIsDisplayed()
        composeRule.onNodeWithText("Missed").assertIsDisplayed()
        composeRule.onNodeWithText("WhatsApp").assertIsDisplayed()
    }

    @Test
    fun favoritesAreShownAndCallable() {
        val called = mutableListOf<String>()
        composeRule.setContent {
            HomeContent(
                favorites = listOf(contact(1, "Alice"), contact(2, "Bob")),
                recentsUiState = RecentsUiState(loading = false),
                onCall = { called.add(it) },
                onRequestPermissions = {},
            )
        }
        composeRule.onNodeWithText("Favorites").assertIsDisplayed()
        composeRule.onNodeWithText("Alice").assertIsDisplayed()
        composeRule.onNodeWithText("Bob").performClick()
        assertEquals(listOf("0401234562"), called)
    }

    @Test
    fun noFavoritesHidesTheFavoritesSection() {
        composeRule.setContent {
            HomeContent(
                favorites = emptyList(),
                recentsUiState = RecentsUiState(loading = false),
                onCall = {},
                onRequestPermissions = {},
            )
        }
        composeRule.onNodeWithText("Favorites").assertDoesNotExist()
        composeRule.onNodeWithText("No calls yet").assertIsDisplayed()
    }

    @Test
    fun recentsRowOpensDetailsAndTheTrailingIconCalls() {
        val called = mutableListOf<String>()
        val opened = mutableListOf<String>()
        val entry = CallLogEntry(
            id = 1,
            number = "0401234567",
            displayName = "Alice",
            type = CallType.INCOMING,
            timestampMillis = 1_720_000_000_000,
            durationSeconds = 60,
        )
        composeRule.setContent {
            HomeContent(
                favorites = emptyList(),
                recentsUiState = RecentsUiState(
                    loading = false,
                    entries = listOf(GroupedCallLogEntry(entry)),
                ),
                onCall = { called.add(it) },
                onRequestPermissions = {},
                onOpenDetails = { opened.add(it) },
            )
        }
        composeRule.onNodeWithText("Alice").performClick()
        assertEquals(listOf("0401234567"), opened)
        assertEquals(emptyList<String>(), called)
        composeRule.onNodeWithContentDescription("Call").performClick()
        assertEquals(listOf("0401234567"), called)
    }
}
