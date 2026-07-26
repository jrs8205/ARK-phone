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
    fun waMeLinkKeepsOnlyDigitsOfAnInternationalNumber() {
        assertEquals("https://wa.me/358445552841", waMeUri("+358 44 555-2841", null).toString())
    }

    @Test
    fun waMeLinkAcceptsTheZeroZeroInternationalForm() {
        assertEquals("https://wa.me/358445552841", waMeUri("00358 44 5552841", null).toString())
    }

    @Test
    fun waMeLinkConvertsANationalNumberWhenTheRegionIsFinland() {
        assertEquals("https://wa.me/358401234567", waMeUri("040 1234567", "fi").toString())
    }

    @Test
    fun waMeLinkIsNotMadeForANationalNumberOutsideFinland() {
        assertEquals(null, waMeUri("040 1234567", "se"))
        assertEquals(null, waMeUri("040 1234567", null))
    }

    @Test
    fun waMeLinkIsNotMadeForServiceCodes() {
        assertEquals(null, waMeUri("116117", "fi"))
    }

    @Test
    fun sourcePackageIsPreferredWhenInstalled() {
        assertEquals(
            "com.whatsapp.w4b",
            preferredWhatsAppPackage("com.whatsapp.w4b") { it == "com.whatsapp.w4b" },
        )
    }

    @Test
    fun anUninstalledSourcePackageFallsBackToTheInstalledVariant() {
        assertEquals(
            "com.whatsapp",
            preferredWhatsAppPackage("com.whatsapp.w4b") { it == "com.whatsapp" },
        )
    }

    @Test
    fun withoutASourcePackageThePersonalVariantWinsOverBusiness() {
        assertEquals("com.whatsapp", preferredWhatsAppPackage(null) { true })
        assertEquals("com.whatsapp.w4b", preferredWhatsAppPackage(null) { it == "com.whatsapp.w4b" })
    }

    @Test
    fun noInstalledVariantMeansNoPackage() {
        assertEquals(null, preferredWhatsAppPackage("com.whatsapp") { false })
    }
}
