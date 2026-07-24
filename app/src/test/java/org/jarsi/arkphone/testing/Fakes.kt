package org.jarsi.arkphone.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.util.PermissionChecker

class FakeCallLogRepository : CallLogRepository {
    val entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    override fun callLog(): Flow<List<CallLogEntry>> = entries
}

class FakeContactsRepository : ContactsRepository {
    val allContacts = MutableStateFlow<List<Contact>>(emptyList())
    val matchesByNumber = mutableMapOf<String, ContactMatch>()
    override fun contacts(): Flow<List<Contact>> = allContacts
    override suspend fun lookupContact(number: String): ContactMatch? = matchesByNumber[number]
}

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state
    override suspend fun setAnnounceCaller(enabled: Boolean) {
        state.value = state.value.copy(announceCaller = enabled)
    }
}

class FakePermissionChecker(private val granted: MutableSet<String> = mutableSetOf()) : PermissionChecker {
    fun grant(permission: String) = granted.add(permission)
    override fun has(permission: String): Boolean = permission in granted
}
