package org.jarsi.arkphone.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArkCodeTest {

    @Test
    fun `a well formed code is valid`() {
        assertTrue(ArkCode.isValid("ARK-7K3M-Q2FP"))
    }

    @Test
    fun `the confusable characters are not in the alphabet`() {
        assertFalse(ArkCode.isValid("ARK-0O1I-LLLL"))
    }

    @Test
    fun `lowercase, short, long and padded codes are invalid`() {
        assertFalse(ArkCode.isValid("ark-7k3m-q2fp"))
        assertFalse(ArkCode.isValid("ARK-7K3M-Q2F"))
        assertFalse(ArkCode.isValid("ARK-7K3M-Q2FPX"))
        assertFalse(ArkCode.isValid(" ARK-7K3M-Q2FP"))
        assertFalse(ArkCode.isValid("ARK-7K3M-Q2FP\n"))
    }

    @Test
    fun `canonicalize repairs pasted text`() {
        assertEquals("ARK-7K3M-Q2FP", ArkCode.canonicalize("  ark-7k3m-q2fp \n"))
        assertEquals("ARK-7K3M-Q2FP", ArkCode.canonicalize("ARK 7K3M Q2FP"))
        assertEquals("ARK-7K3M-Q2FP", ArkCode.canonicalize("7k3mq2fp"))
    }

    @Test
    fun `canonicalize refuses anything that is not a code`() {
        assertNull(ArkCode.canonicalize("hello"))
        assertNull(ArkCode.canonicalize("ARK-0O1I-LLLL"))
        assertNull(ArkCode.canonicalize(""))
    }

    @Test
    fun `the link key is the last nine digits of a number`() {
        assertEquals("445552841", arkLinkKey("+358 44 5552841"))
        assertEquals("445552841", arkLinkKey("044 555 2841"))
        assertEquals("91234567", arkLinkKey("09 1234567"))
    }
}
