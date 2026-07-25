package org.jarsi.arkphone.ui.incall

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class InCallFinishGuardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun finishesWhenNoCallArrivesWithinGrace() {
        var finishCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            InCallFinishGuard(hasCall = false, graceMillis = 2_000L) { finishCount++ }
        }
        composeRule.mainClock.advanceTimeBy(1_900L)
        assertEquals(0, finishCount)
        composeRule.mainClock.advanceTimeBy(200L)
        assertEquals(1, finishCount)
    }

    @Test
    fun doesNotFinishWhileCallIsPresent() {
        var finishCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            InCallFinishGuard(hasCall = true, graceMillis = 2_000L) { finishCount++ }
        }
        composeRule.mainClock.advanceTimeBy(5_000L)
        assertEquals(0, finishCount)
    }

    @Test
    fun finishesAfterAShortEndedGraceWhenASeenCallEnds() {
        var finishCount = 0
        var hasCall by mutableStateOf(true)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            InCallFinishGuard(hasCall = hasCall, graceMillis = 2_000L) { finishCount++ }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        hasCall = false
        composeRule.waitForIdle()
        // The "call ended" state stays visible for a moment before closing.
        composeRule.mainClock.advanceTimeBy(500L)
        assertEquals(0, finishCount)
        composeRule.mainClock.advanceTimeBy(1_100L)
        assertEquals(1, finishCount)
    }

    @Test
    fun callArrivingWithinGraceSuppressesTheGraceFinish() {
        var finishCount = 0
        var hasCall by mutableStateOf(false)
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            InCallFinishGuard(hasCall = hasCall, graceMillis = 2_000L) { finishCount++ }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        hasCall = true
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(5_000L)
        assertEquals(0, finishCount)
    }
}
