package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.Contact

interface ContactsRepository {
    fun contacts(): Flow<List<Contact>>
}
