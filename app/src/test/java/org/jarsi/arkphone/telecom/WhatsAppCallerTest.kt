package org.jarsi.arkphone.telecom

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WhatsAppCallerTest {

    @Test
    fun rowMatchesByNumberAcrossFormats() {
        assertTrue(whatsAppRowMatches("+358 44 5552841", null, "358445552841", "Whatever"))
        assertTrue(whatsAppRowMatches("0445552841", null, "+358 44 5552841", null))
        assertFalse(whatsAppRowMatches("+358 44 5552841", null, "358401112223", null))
    }

    @Test
    fun rowMatchesByNameOnlyWhenThereIsNoNumberToCompare() {
        assertTrue(whatsAppRowMatches(null, "Matti Meikäläinen", null, "Matti Meikäläinen"))
        assertFalse(whatsAppRowMatches(null, "Matti Meikäläinen", null, "Muu Nimi"))
        assertFalse(whatsAppRowMatches(null, null, null, "Matti Meikäläinen"))
    }

    @Test
    fun aRowNumberBeatsANameMatch() {
        assertFalse(
            whatsAppRowMatches("+358 44 5552841", "Matti", "358401112223", "Matti"),
        )
    }

    @Test
    fun waMeLinkKeepsOnlyDigits() {
        assertEquals("https://wa.me/358445552841", waMeUri("+358 44 555-2841").toString())
    }
}
