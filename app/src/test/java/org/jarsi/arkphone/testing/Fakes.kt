package org.jarsi.arkphone.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.data.SimRepository
import org.jarsi.arkphone.data.WhatsAppCallLogRepository
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.data.model.SimCard
import org.jarsi.arkphone.data.model.WhatsAppCallRecord
import org.jarsi.arkphone.util.PermissionChecker

class FakeCallLogRepository : CallLogRepository {
    val entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val deletedNumbers = mutableListOf<String>()
    override fun callLog(): Flow<List<CallLogEntry>> = entries
    override suspend fun deleteCallsFor(number: String): Boolean {
        deletedNumbers += number
        return true
    }
}

class FakeWhatsAppCallLogRepository : WhatsAppCallLogRepository {
    val recorded = mutableListOf<WhatsAppCallRecord>()
    val entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val deletedNumbers = mutableListOf<String>()
    val deletedNames = mutableListOf<String>()
    override fun calls(): Flow<List<CallLogEntry>> = entries
    override suspend fun record(call: WhatsAppCallRecord) {
        recorded += call
    }
    override suspend fun deleteCallsFor(number: String) {
        deletedNumbers += number
    }
    override suspend fun deleteCallsForName(name: String) {
        deletedNames += name
    }
}

class FakeBlockedNumbersRepository(
    private val canBlock: Boolean = true,
) : BlockedNumbersRepository {
    val blocked = mutableSetOf<String>()
    override suspend fun canBlock(): Boolean = canBlock
    override suspend fun isBlocked(number: String): Boolean = number in blocked
    override suspend fun block(number: String): Boolean = blocked.add(number)
    override suspend fun unblock(number: String): Boolean = blocked.remove(number)
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
    override suspend fun setAnnounceMode(mode: AnnounceMode) {
        state.value = state.value.copy(announceMode = mode)
    }
    override suspend fun setAnnounceIntervalSeconds(seconds: Int) {
        state.value = state.value.copy(announceIntervalSeconds = seconds)
    }
    override suspend fun setAnnounceWhatsApp(enabled: Boolean) {
        state.value = state.value.copy(announceWhatsApp = enabled)
    }
}

class FakeSimRepository(var sims: List<SimCard> = emptyList()) : SimRepository {
    override suspend fun activeSims(): List<SimCard> = sims
}

class FakePermissionChecker(private val granted: MutableSet<String> = mutableSetOf()) : PermissionChecker {
    fun grant(permission: String) = granted.add(permission)
    override fun has(permission: String): Boolean = permission in granted
}
