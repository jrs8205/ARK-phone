package org.jarsi.arkphone.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun identicalDigitsMatchIgnoringFormatting() {
        assertTrue(sameCaller("0401234567", "040 123 4567"))
    }

    @Test
    fun nationalAndInternationalMobileFormsMatch() {
        assertTrue(sameCaller("0401234567", "+358 40 1234567"))
    }

    @Test
    fun shortLandlineNationalFormMatchesTheInternationalForm() {
        // A landline's digits fall under the nine-digit shared tail, so the
        // trunk-zero form must be recognized explicitly.
        assertTrue(sameCaller("09 1234567", "+358 9 1234567"))
        assertTrue(sameCaller("+358 9 1234567", "09 1234567"))
        assertTrue(sameCaller("09 1234567", "00358 9 1234567"))
    }

    @Test
    fun differentNumbersDoNotMatch() {
        assertFalse(sameCaller("0401234567", "0409999999"))
        assertFalse(sameCaller("09 1234567", "+358 9 7654321"))
        assertFalse(sameCaller("09 1234567", "+358 40 1234567"))
    }

    @Test
    fun blankNumbersNeverMatch() {
        assertFalse(sameCaller("", "0401234567"))
        assertFalse(sameCaller("abc", "def"))
        assertFalse(sameCaller("", ""))
        assertFalse(sameCaller("   ", " "))
    }

    @Test
    fun alphanumericSenderIdsMatchThemselves() {
        // SMS sender ids like "INFO" or "KLARNA" carry no digits at all:
        // the block list must still be able to hold and match them.
        assertTrue(sameCaller("INFO", "INFO"))
        assertTrue(sameCaller("Klarna", "KLARNA"))
        assertTrue(sameCaller(" INFO ", "INFO"))
    }
}
