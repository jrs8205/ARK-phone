package org.jarsi.arkphone.ui.contacts

import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

class FrequentContactsTest {

    private fun contact(id: Long, name: String, number: String?, starred: Boolean = false) =
        Contact(id, name, number, null, starred)

    private fun call(id: Long, number: String) = CallLogEntry(
        id = id,
        number = number,
        displayName = null,
        type = CallType.OUTGOING,
        timestampMillis = id,
        durationSeconds = 10,
    )

    @Test
    fun ordersByCallCountAndSkipsFavoritesAndStrangers() {
        val contacts = listOf(
            contact(1, "Harvoin", "0401111111"),
            contact(2, "Usein", "0402222222"),
            contact(3, "Suosikki", "0403333333", starred = true),
        )
        val log = listOf(
            call(1, "0402222222"),
            call(2, "+358 40 2222222"),
            call(3, "0401111111"),
            call(4, "0403333333"),
            call(5, "0409999999"),
        )
        assertEquals(
            listOf("Usein", "Harvoin"),
            frequentContacts(contacts, log).map { it.displayName },
        )
    }

    @Test
    fun limitsTheListAndIgnoresNumberlessContacts() {
        val contacts = (1L..8L).map { contact(it, "C$it", "04000000$it") } +
            contact(9, "EiNumeroa", null)
        val log = (1L..8L).flatMap { id ->
            List(id.toInt()) { call(id * 100 + it, "04000000$id") }
        }
        val frequent = frequentContacts(contacts, log, limit = 5)
        assertEquals(listOf("C8", "C7", "C6", "C5", "C4"), frequent.map { it.displayName })
    }
}
