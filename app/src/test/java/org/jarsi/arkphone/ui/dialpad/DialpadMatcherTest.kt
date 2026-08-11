package org.jarsi.arkphone.ui.dialpad

import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialpadMatcherTest {

    private fun contact(id: Long, name: String, number: String) = Contact(
        id = id, displayName = name, phoneNumber = number, photoUri = null, starred = false,
    )

    private fun entry(
        id: Long,
        number: String,
        timestamp: Long = 0L,
        type: CallType = CallType.OUTGOING,
    ) = CallLogEntry(
        id = id, number = number, displayName = null, type = type,
        timestampMillis = timestamp, durationSeconds = 0,
    )

    /** Finnish-shaped stand-in for PhoneNumberUtils.formatNumberToE164. */
    private fun fakeE164(number: String): String? {
        val stripped = number.filter { it.isDigit() || it == '+' }
        return when {
            stripped.startsWith("+358") && stripped.length == 13 -> stripped
            stripped.startsWith("0") && stripped.length == 10 -> "+358" + stripped.drop(1)
            else -> null
        }
    }

    private fun filterHistory(
        entries: List<CallLogEntry>,
        contacts: List<Contact> = emptyList(),
        query: String,
    ) = DialpadMatcher.filterHistory(entries, contacts, query, ::fakeE164)

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

    @Test
    fun historyIsSuggestedByTypedPrefix() {
        val entries = listOf(entry(1, "0401234567"))
        assertEquals(listOf("0401234567"), filterHistory(entries, query = "040"))
    }

    @Test
    fun historyNeedsAtLeastThreeTypedCharacters() {
        val entries = listOf(entry(1, "0401234567"))
        assertTrue(filterHistory(entries, query = "04").isEmpty())
        assertTrue(filterHistory(entries, query = "").isEmpty())
    }

    @Test
    fun nationalTypingMatchesAnInternationallyStoredNumber() {
        val entries = listOf(entry(1, "+358 40 123 4567"))
        assertEquals(listOf("+358 40 123 4567"), filterHistory(entries, query = "040"))
    }

    @Test
    fun internationalTypingMatchesANationallyStoredNumber() {
        val entries = listOf(entry(1, "0401234567"))
        assertEquals(listOf("0401234567"), filterHistory(entries, query = "+35840"))
    }

    @Test
    fun contactNumbersAreLeftToTheContactSuggestions() {
        val entries = listOf(entry(1, "0401234567"))
        val contacts = listOf(contact(1, "Matti", "+358 40 123 4567"))
        assertTrue(filterHistory(entries, contacts, query = "040").isEmpty())
    }

    @Test
    fun duplicateFormsCollapseToTheNewestRow() {
        val entries = listOf(
            entry(1, "040 123 4567", timestamp = 1_000),
            entry(2, "+358401234567", timestamp = 2_000),
        )
        assertEquals(listOf("+358401234567"), filterHistory(entries, query = "040"))
    }

    @Test
    fun newestMatchingNumberComesFirst() {
        val entries = listOf(
            entry(1, "0401111111", timestamp = 1_000),
            entry(2, "0402222222", timestamp = 2_000),
        )
        assertEquals(
            listOf("0402222222", "0401111111"),
            filterHistory(entries, query = "040"),
        )
    }

    @Test
    fun blockedAndNumberlessRowsAreNeverSuggested() {
        val entries = listOf(
            entry(1, "0401234567", type = CallType.BLOCKED),
            entry(2, ""),
        )
        assertTrue(filterHistory(entries, query = "040").isEmpty())
    }
}
