package org.jarsi.arkphone.ui.dialpad

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DialpadGridTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longPressOnZeroEmitsPlus() {
        val pressed = mutableListOf<Char>()
        composeRule.setContent { DialpadGrid(onKey = { pressed.add(it) }) }
        composeRule.onNodeWithText("0").performTouchInput { longClick() }
        assertEquals(listOf('+'), pressed)
    }

    @Test
    fun tapOnZeroStillEmitsZero() {
        val pressed = mutableListOf<Char>()
        composeRule.setContent { DialpadGrid(onKey = { pressed.add(it) }) }
        composeRule.onNodeWithText("0").performClick()
        assertEquals(listOf('0'), pressed)
    }

    @Test
    fun longPressOnOneCallsVoicemail() {
        val pressed = mutableListOf<Char>()
        var voicemails = 0
        composeRule.setContent {
            DialpadGrid(onKey = { pressed.add(it) }, onVoicemail = { voicemails++ })
        }
        composeRule.onNodeWithText("1").performTouchInput { longClick() }
        assertEquals(1, voicemails)
        assertEquals(emptyList<Char>(), pressed)
    }

    @Test
    fun longPressOnDigitsTwoToNineTriggersSpeedDial() {
        val pressed = mutableListOf<Char>()
        val speedDials = mutableListOf<Int>()
        composeRule.setContent {
            DialpadGrid(onKey = { pressed.add(it) }, onSpeedDial = { speedDials.add(it) })
        }
        composeRule.onNodeWithText("5").performTouchInput { longClick() }
        assertEquals(listOf(5), speedDials)
        assertEquals(emptyList<Char>(), pressed)
    }
}
