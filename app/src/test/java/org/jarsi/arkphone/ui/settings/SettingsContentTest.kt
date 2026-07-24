package org.jarsi.arkphone.ui.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
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
        onModeChanged: (AnnounceMode) -> Unit = {},
        onIntervalChanged: (Int) -> Unit = {},
        onOpenSimInfo: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsContent(
                settings = settings,
                onAnnounceModeChanged = onModeChanged,
                onAnnounceIntervalChanged = onIntervalChanged,
                onOpenSimInfo = onOpenSimInfo,
                onBack = onBack,
            )
        }
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
    fun simRowOpensSimInfo() {
        var opened = false
        setContent(onOpenSimInfo = { opened = true })
        composeRule.onNodeWithText("SIM cards").performClick()
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
