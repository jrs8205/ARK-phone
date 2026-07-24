package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.ContactMatch

interface ContactsRepository {
    fun contacts(): Flow<List<Contact>>

    /** Resolves a number to a saved contact, or null when there is no match. */
    suspend fun lookupContact(number: String): ContactMatch?
}
