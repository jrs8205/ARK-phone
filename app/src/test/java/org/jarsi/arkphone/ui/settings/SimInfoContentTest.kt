package org.jarsi.arkphone.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.SimCard
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SimInfoContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun sim(
        id: Int = 1,
        slot: Int = 0,
        name: String? = "DNA",
        number: String? = "+358401234567",
        embedded: Boolean = false,
    ) = SimCard(
        subscriptionId = id,
        slotIndex = slot,
        displayName = name,
        carrierName = name,
        number = number,
        countryIso = "FI",
        isEmbedded = embedded,
    )

    private fun setContent(
        uiState: SimInfoUiState,
        onOpenSystemSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            SimInfoContent(
                uiState = uiState,
                onBack = {},
                onRequestNumberPermission = {},
                onOpenSystemSettings = onOpenSystemSettings,
            )
        }
    }

    @Test
    fun showsCarrierNumberAndEsimSlot() {
        setContent(
            SimInfoUiState(
                sims = listOf(sim(), sim(id = 2, slot = 1, name = "Elisa", number = null, embedded = true)),
                loading = false,
            ),
        )
        composeRule.onNodeWithText("+358401234567").assertIsDisplayed()
        composeRule.onNodeWithText("SIM 1").assertIsDisplayed()
        composeRule.onNodeWithText("eSIM (SIM 2)").assertIsDisplayed()
    }

    @Test
    fun emptyStateIsShownWithoutSims() {
        setContent(SimInfoUiState(sims = emptyList(), loading = false))
        composeRule.onNodeWithText("SIM information is not available.").assertIsDisplayed()
    }

    @Test
    fun systemSettingsButtonInvokesCallback() {
        var opened = false
        setContent(
            SimInfoUiState(sims = listOf(sim()), loading = false),
            onOpenSystemSettings = { opened = true },
        )
        composeRule.onNodeWithText("Open system SIM settings").performClick()
        assertTrue(opened)
    }
}
