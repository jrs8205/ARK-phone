package org.jarsi.arkphone.ui.dialpad

import org.jarsi.arkphone.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialpadMatcherTest {

    private fun contact(id: Long, name: String, number: String) = Contact(
        id = id, displayName = name, phoneNumber = number, photoUri = null, starred = false,
    )

    @Test
    fun mapsLettersToDigits() {
        assertEquals('2', DialpadMatcher.digitFor('a'))
        assertEquals('5', DialpadMatcher.digitFor('K'))
        assertEquals('9', DialpadMatcher.digitFor('z'))
        // Nordic letters follow the traditional keypad layout
        assertEquals('2', DialpadMatcher.digitFor('ä'))
        assertEquals('2', DialpadMatcher.digitFor('å'))
        assertEquals('6', DialpadMatcher.digitFor('ö'))
    }

    @Test
    fun matchesWordStarts() {
        assertTrue(DialpadMatcher.matches("Matti Meikäläinen", "628"))   // "mat"
        assertTrue(DialpadMatcher.matches("Matti Meikäläinen", "634"))   // "mei"
        assertFalse(DialpadMatcher.matches("Matti Meikäläinen", "999"))
    }

    @Test
    fun filtersByNameAndNumber() {
        val contacts = listOf(
            contact(1, "Matti", "0401111111"),
            contact(2, "Pekka", "0502222222"),
        )
        assertEquals(listOf("Matti"), DialpadMatcher.filter(contacts, "628").map { it.displayName })
        assertEquals(listOf("Pekka"), DialpadMatcher.filter(contacts, "050").map { it.displayName })
        assertTrue(DialpadMatcher.filter(contacts, "").isEmpty())
    }
}
