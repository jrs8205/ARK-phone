package org.jarsi.arkphone.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Contact
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
}
