package org.jarsi.arkphone.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.data.model.SimAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SettingsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        settings: Settings = Settings(),
        hasNotificationAccess: Boolean = true,
        simAccounts: List<SimAccount> = emptyList(),
        onModeChanged: (AnnounceMode) -> Unit = {},
        onIntervalChanged: (Int) -> Unit = {},
        onWhatsAppChanged: (Boolean) -> Unit = {},
        onGrantNotificationAccess: () -> Unit = {},
        onOpenSimInfo: () -> Unit = {},
        onOpenCallSettings: () -> Unit = {},
        onCallSimChanged: (String?) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsContent(
                settings = settings,
                hasNotificationAccess = hasNotificationAccess,
                simAccounts = simAccounts,
                onAnnounceModeChanged = onModeChanged,
                onAnnounceIntervalChanged = onIntervalChanged,
                onAnnounceWhatsAppChanged = onWhatsAppChanged,
                onGrantNotificationAccess = onGrantNotificationAccess,
                onOpenSimInfo = onOpenSimInfo,
                onOpenCallSettings = onOpenCallSettings,
                onCallSimAccountChanged = onCallSimChanged,
                onBack = onBack,
            )
        }
    }

    private val twoSims = listOf(
        SimAccount("sub-1", "DNA", "+358401234567"),
        SimAccount("sub-2", "Elisa", null),
    )

    @Test
    fun theSimChoiceIsHiddenWithASingleSim() {
        setContent(simAccounts = listOf(SimAccount("sub-1", "DNA", null)))
        composeRule.onNodeWithText("SIM for calls").assertDoesNotExist()
    }

    @Test
    fun theSimChoiceListsTheSystemDefaultAndEverySim() {
        setContent(simAccounts = twoSims)
        composeRule.onNodeWithText("SIM for calls").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("System default").assertIsSelected()
        composeRule.onNodeWithText("DNA (+358401234567)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Elisa").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun choosingASimReportsItsAccountId() {
        val chosen = mutableListOf<String?>()
        setContent(simAccounts = twoSims, onCallSimChanged = { chosen += it })
        composeRule.onNodeWithText("Elisa").performScrollTo().performClick()
        assertEquals(listOf("sub-2"), chosen)
    }

    @Test
    fun theChosenSimIsShownAsSelected() {
        setContent(settings = Settings(callSimAccountId = "sub-2"), simAccounts = twoSims)
        composeRule.onNodeWithText("Elisa").performScrollTo().assertIsSelected()
    }

    @Test
    fun choosingTheSystemDefaultClearsTheChoice() {
        val chosen = mutableListOf<String?>()
        setContent(
            settings = Settings(callSimAccountId = "sub-2"),
            simAccounts = twoSims,
            onCallSimChanged = { chosen += it },
        )
        composeRule.onNodeWithText("System default").performScrollTo().performClick()
        assertEquals(listOf<String?>(null), chosen)
    }

    @Test
    fun selectingVoiceOnlyReportsTheMode() {
        val changes = mutableListOf<AnnounceMode>()
        setContent(onModeChanged = { changes.add(it) })
        composeRule.onNodeWithText("Off").assertIsSelected()
        composeRule.onNodeWithText("Voice only").performClick()
        assertEquals(listOf(AnnounceMode.VOICE_ONLY), changes)
    }

    @Test
    fun sliderIsOnlyVisibleInVoiceOnlyMode() {
        setContent(settings = Settings(announceMode = AnnounceMode.WITH_RINGTONE))
        composeRule.onNodeWithText("Repeat every 6 seconds").assertDoesNotExist()
    }

    @Test
    fun sliderShowsAndWritesTheInterval() {
        val changes = mutableListOf<Int>()
        setContent(
            settings = Settings(announceMode = AnnounceMode.VOICE_ONLY, announceIntervalSeconds = 6),
            onIntervalChanged = { changes.add(it) },
        )
        composeRule.onNodeWithText("Repeat every 6 seconds").assertIsDisplayed()
        composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(9f) }
        assertEquals(listOf(9), changes)
    }

    @Test
    fun whatsAppToggleWritesTheSetting() {
        val changes = mutableListOf<Boolean>()
        setContent(onWhatsAppChanged = { changes.add(it) })
        composeRule.onNodeWithText("Announce WhatsApp calls").performScrollTo().performClick()
        assertEquals(listOf(true), changes)
    }

    @Test
    fun whatsAppEnabledShowsTheSliderEvenWithModeOff() {
        setContent(settings = Settings(announceMode = AnnounceMode.OFF, announceWhatsApp = true))
        composeRule.onNodeWithText("Repeat every 6 seconds").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun missingNotificationAccessShowsTheGrantButton() {
        var asked = false
        setContent(
            settings = Settings(announceWhatsApp = true),
            hasNotificationAccess = false,
            onGrantNotificationAccess = { asked = true },
        )
        composeRule.onNodeWithText("Grant notification access").performScrollTo().performClick()
        assertTrue(asked)
    }

    @Test
    fun simRowOpensSimInfo() {
        var opened = false
        setContent(onOpenSimInfo = { opened = true })
        composeRule.onNodeWithText("SIM cards").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun callSettingsRowOpensSystemCallSettings() {
        var opened = false
        setContent(onOpenCallSettings = { opened = true })
        composeRule.onNodeWithText("Call settings").performScrollTo().performClick()
        assertTrue(opened)
    }

    @Test
    fun backButtonInvokesCallback() {
        var backed = false
        setContent(onBack = { backed = true })
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backed)
    }
}
