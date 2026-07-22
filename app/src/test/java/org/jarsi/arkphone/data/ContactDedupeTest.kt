package org.jarsi.arkphone.data

import org.jarsi.arkphone.data.model.Contact
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactDedupeTest {

    private fun contact(id: Long, name: String, number: String?) =
        Contact(id = id, displayName = name, phoneNumber = number, photoUri = null, starred = false)

    @Test
    fun keepsFirstNumberPerContact() {
        val rows = listOf(
            contact(1, "Alice", "+358401111111"),
            contact(1, "Alice", "+358402222222"),
            contact(2, "Bob", "+358403333333"),
        )
        val result = dedupeByContactId(rows)
        assertEquals(2, result.size)
        assertEquals("+358401111111", result[0].phoneNumber)
    }

    @Test
    fun sortsByDisplayName() {
        val rows = listOf(contact(2, "Bob", "1"), contact(1, "alice", "2"))
        assertEquals(listOf("alice", "Bob"), dedupeByContactId(rows).map { it.displayName })
    }
}
