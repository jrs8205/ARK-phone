package org.jarsi.arkphone.ui.dialpad

import org.jarsi.arkphone.data.model.Contact

object DialpadMatcher {

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
