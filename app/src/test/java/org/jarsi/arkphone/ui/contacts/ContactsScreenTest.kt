package org.jarsi.arkphone.ui.contacts

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ContactsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val contact = Contact(
        id = 7,
        displayName = "Matti Meikäläinen",
        phoneNumber = "0401234567",
        photoUri = null,
        starred = false,
    )

    private fun setContent(
        onCall: (String) -> Unit = {},
        onOpenContact: (Long) -> Unit = {},
    ) {
        composeRule.setContent {
            ContactsContent(
                uiState = ContactsUiState(
                    loading = false,
                    hasPermission = true,
                    others = listOf(contact),
                ),
                onQueryChange = {},
                onCall = onCall,
                onRequestPermissions = {},
                onOpenContact = onOpenContact,
            )
        }
    }

    @Test
    fun rowTapOpensTheContactCardInsteadOfCalling() {
        var opened: Long? = null
        var called: String? = null
        setContent(onCall = { called = it }, onOpenContact = { opened = it })
        composeRule.onNodeWithText("Matti Meikäläinen").performClick()
        assertEquals(7L, opened)
        assertNull(called)
    }

    @Test
    fun trailingIconCallsTheContact() {
        var called: String? = null
        setContent(onCall = { called = it })
        composeRule.onNodeWithContentDescription("Call").performClick()
        assertEquals("0401234567", called)
    }

    @Test
    fun favoritesAndOthersGetTheirOwnSectionHeaders() {
        composeRule.setContent {
            ContactsContent(
                uiState = ContactsUiState(
                    loading = false,
                    hasPermission = true,
                    favorites = listOf(contact.copy(id = 1, displayName = "Suosikki Sanna", starred = true)),
                    others = listOf(contact),
                ),
                onQueryChange = {},
                onCall = {},
                onRequestPermissions = {},
            )
        }
        composeRule.onNodeWithText("Favorites").assertExists()
        composeRule.onNodeWithText("All contacts").assertExists()
    }
}
