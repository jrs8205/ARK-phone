package org.jarsi.arkphone.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.data.model.SimAccount
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
        hasScreeningRole: Boolean = true,
        simAccounts: List<SimAccount> = emptyList(),
        onBlockHiddenChanged: (Boolean) -> Unit = {},
        onAddPrefix: (String) -> Unit = {},
        onRemovePrefix: (String) -> Unit = {},
        onRequestScreeningRole: () -> Unit = {},
        onBlockingSimChanged: (String?) -> Unit = {},
    ) {
        composeRule.setContent {
            BlockingContent(
                settings = settings,
                hasScreeningRole = hasScreeningRole,
                simAccounts = simAccounts,
                onBlockingSimAccountChanged = onBlockingSimChanged,
                onBack = {},
                onBlockHiddenNumbersChanged = onBlockHiddenChanged,
                onBlockUnknownCallersChanged = {},
                onAllowRepeatCallersChanged = {},
                onAddBlockedPrefix = onAddPrefix,
                onRemoveBlockedPrefix = onRemovePrefix,
                onRequestScreeningRole = onRequestScreeningRole,
            )
        }
    }

    @Test
    fun missingScreeningRoleShowsTheBannerAndRequests() {
        var requested = 0
        setContent(hasScreeningRole = false, onRequestScreeningRole = { requested++ })
        composeRule.onNodeWithText("Set ARK-phone").performClick()
        assertEquals(1, requested)
    }

    @Test
    fun heldScreeningRoleHidesTheBanner() {
        setContent(hasScreeningRole = true)
        composeRule.onNodeWithText("Set ARK-phone").assertDoesNotExist()
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
        composeRule.onNodeWithText("e.g. +358700 or 0700")
            .performScrollTo()
            .performTextInput("0700")
        // The allowed-numbers section has its own Add button higher up.
        composeRule.onAllNodesWithText("Add")[1].performScrollTo().performClick()
        assertEquals("0700", added)
    }

    @Test
    fun removingAListedPrefixReportsIt() {
        var removed: String? = null
        setContent(
            settings = Settings(blockedPrefixes = setOf("+358700")),
            onRemovePrefix = { removed = it },
        )
        composeRule.onNodeWithText("+358700").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove").performScrollTo().performClick()
        assertEquals("+358700", removed)
    }

    private val twoSims = listOf(
        SimAccount("sub-1", "DNA", null),
        SimAccount("sub-2", "Elisa", null),
    )

    @Test
    fun theSimRestrictionIsHiddenWithASingleSim() {
        setContent(simAccounts = listOf(SimAccount("sub-1", "DNA", null)))
        composeRule.onAllNodesWithText("Apply the rules to").assertCountEquals(0)
    }

    @Test
    fun theSimRestrictionDefaultsToEverySim() {
        setContent(simAccounts = twoSims)
        composeRule.onNodeWithText("Apply the rules to").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("All SIMs").assertIsSelected()
    }

    @Test
    fun choosingASimReportsTheRestriction() {
        val chosen = mutableListOf<String?>()
        setContent(simAccounts = twoSims, onBlockingSimChanged = { chosen += it })
        composeRule.onNodeWithText("Elisa").performScrollTo().performClick()
        assertEquals(listOf("sub-2"), chosen)
    }

    @Test
    fun choosingAllSimsClearsTheRestriction() {
        val chosen = mutableListOf<String?>()
        setContent(
            settings = Settings(blockingSimAccountId = "sub-2"),
            simAccounts = twoSims,
            onBlockingSimChanged = { chosen += it },
        )
        composeRule.onNodeWithText("All SIMs").performScrollTo().performClick()
        assertEquals(listOf<String?>(null), chosen)
    }
}
