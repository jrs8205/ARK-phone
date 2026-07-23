package org.jarsi.arkphone.util

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun formatsSecondsUnderAnHour() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:45", formatDuration(45))
        assertEquals("12:03", formatDuration(12 * 60 + 3))
    }

    @Test
    fun formatsHours() {
        assertEquals("1:02:03", formatDuration(3600 + 2 * 60 + 3))
    }

    @Test
    fun formatsWithAsciiDigitsRegardlessOfDefaultLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("12:03", formatDuration(12 * 60 + 3))
            assertEquals("1:02:03", formatDuration(3600 + 2 * 60 + 3))
        } finally {
            Locale.setDefault(original)
        }
    }
}
