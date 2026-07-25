package org.jarsi.arkphone.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BlockingContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        settings: Settings = Settings(),
        onBlockHiddenChanged: (Boolean) -> Unit = {},
        onAddPrefix: (String) -> Unit = {},
        onRemovePrefix: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            BlockingContent(
                settings = settings,
                onBack = {},
                onBlockHiddenNumbersChanged = onBlockHiddenChanged,
                onBlockUnknownCallersChanged = {},
                onAllowRepeatCallersChanged = {},
                onAddBlockedPrefix = onAddPrefix,
                onRemoveBlockedPrefix = onRemovePrefix,
            )
        }
    }

    @Test
    fun togglingHiddenNumbersReportsTheChange() {
        var toggled: Boolean? = null
        setContent(onBlockHiddenChanged = { toggled = it })
        composeRule.onNodeWithText("Block hidden numbers").performClick()
        assertEquals(true, toggled)
    }

    @Test
    fun addingAPrefixReportsIt() {
        var added: String? = null
        setContent(onAddPrefix = { added = it })
        composeRule.onNodeWithText("e.g. +358700 or 0700").performTextInput("0700")
        composeRule.onNodeWithText("Add").performClick()
        assertEquals("0700", added)
    }

    @Test
    fun removingAListedPrefixReportsIt() {
        var removed: String? = null
        setContent(
            settings = Settings(blockedPrefixes = setOf("+358700")),
            onRemovePrefix = { removed = it },
        )
        composeRule.onNodeWithText("+358700").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove").performClick()
        assertEquals("+358700", removed)
    }
}
