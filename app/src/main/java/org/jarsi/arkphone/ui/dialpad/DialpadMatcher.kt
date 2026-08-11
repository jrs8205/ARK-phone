package org.jarsi.arkphone.ui.dialpad

import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.Contact

object DialpadMatcher {

    fun filterHistory(
        entries: List<CallLogEntry>,
        contacts: List<Contact>,
        query: String,
        e164: (String) -> String?,
    ): List<String> {
        val typed = query.dialableCharacters()
        if (typed.length < HISTORY_MIN_TYPED) return emptyList()
        val contactKeys = contacts.mapNotNull { it.phoneNumber }
            .map { canonical(it, e164) }
            .toSet()
        val seen = mutableSetOf<String>()
        val result = mutableListOf<String>()
        for (entry in entries.sortedByDescending { it.timestampMillis }) {
            if (entry.type == CallType.BLOCKED) continue
            val stripped = entry.number.dialableCharacters()
            if (stripped.isEmpty()) continue
            val key = e164(stripped) ?: stripped
            if (key in contactKeys || !seen.add(key)) continue
            if (dialForms(stripped, e164).any { it.startsWith(typed) }) result += entry.number
        }
        return result
    }

    private const val HISTORY_MIN_TYPED = 3

    private fun String.dialableCharacters() = filter { it.isDigit() || it == '+' }

    private fun canonical(number: String, e164: (String) -> String?): String {
        val stripped = number.dialableCharacters()
        return e164(stripped) ?: stripped
    }

    /** Every form the number could be typed in: as stored, as E164, and as the
     *  trunk-0 national form. The national form is reconstructed by guessing the
     *  country-code length and round-tripping the candidate through [e164], so
     *  no country table is needed. */
    private fun dialForms(stripped: String, e164: (String) -> String?): Set<String> {
        val forms = mutableSetOf(stripped)
        val canonical = e164(stripped) ?: return forms
        forms += canonical
        for (ccLength in 1..3) {
            val candidate = "0" + canonical.drop(1 + ccLength)
            if (e164(candidate) == canonical) {
                forms += candidate
                break
            }
        }
        return forms
    }

    private val keys = mapOf(
        '2' to "abcäå", '3' to "def", '4' to "ghi", '5' to "jkl",
        '6' to "mnoö", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
    )
    private val letterToDigit: Map<Char, Char> =
        keys.flatMap { (digit, letters) -> letters.map { it to digit } }.toMap()

    fun digitFor(letter: Char): Char? = letterToDigit[letter.lowercaseChar()]

    fun matches(name: String, digits: String): Boolean {
        if (digits.isEmpty()) return true
        return name.split(' ').any { word ->
            val encoded = word.mapNotNull { digitFor(it) }.joinToString("")
            encoded.startsWith(digits)
        }
    }

    fun filter(contacts: List<Contact>, query: String): List<Contact> {
        if (query.isEmpty()) return emptyList()
        return contacts.filter { contact ->
            matches(contact.displayName, query) || contact.phoneNumber?.contains(query) == true
        }
    }
}
