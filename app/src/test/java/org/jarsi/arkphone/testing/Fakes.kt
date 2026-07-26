package org.jarsi.arkphone.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.data.SimRepository
import org.jarsi.arkphone.data.SpeedDialRepository
import org.jarsi.arkphone.data.WhatsAppCallLogRepository
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.data.model.SimCard
import org.jarsi.arkphone.data.model.WhatsAppCallRecord
import org.jarsi.arkphone.util.PermissionChecker

class FakeCallLogRepository : CallLogRepository {
    val entries = MutableStateFlow<List<CallLogEntry>>(emptyList())
    val deletedNumbers = mutableListOf<String>()
    var refreshCalls = 0
    var callLogCollections = 0
    val callsSinceRequests = mutableListOf<Long>()
    val reclassifyRequests = mutableListOf<Pair<String, Long>>()
    val reclassifyResults = ArrayDeque<Boolean>()
    override fun callLog(): Flow<List<CallLogEntry>> {
        callLogCollections++
        return entries
    }
    override suspend fun callsSince(sinceMillis: Long): List<CallLogEntry> {
        callsSinceRequests += sinceMillis
        return entries.value.filter { it.timestampMillis >= sinceMillis }
    }
    override fun refresh() {
        refreshCalls++
    }
    override suspend fun deleteCallsFor(number: String): Boolean {
        deletedNumbers += number
        return true
    }
    override suspend fun reclassifyLatestRejectionAsBlocked(
        number: String,
        notBeforeMillis: Long,
    ): Boolean {
        reclassifyRequests += number to notBeforeMillis
        return reclassifyResults.removeFirstOrNull() ?: true
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
    val detailsById = mutableMapOf<Long, ContactDetails>()
    var refreshCalls = 0
    var lookupCalls = 0
    override fun contacts(): Flow<List<Contact>> = allContacts
    override fun refresh() {
        refreshCalls++
    }
    override suspend fun lookupContact(number: String): ContactMatch? {
        lookupCalls++
        return matchesByNumber[number]
    }
    override suspend fun contactDetails(contactId: Long): ContactDetails? = detailsById[contactId]
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
    override suspend fun setBlockAllCallers(enabled: Boolean) {
        state.value = state.value.copy(blockAllCallers = enabled)
    }
    override suspend fun setBlockHiddenNumbers(enabled: Boolean) {
        state.value = state.value.copy(blockHiddenNumbers = enabled)
    }
    override suspend fun setBlockUnknownCallers(enabled: Boolean) {
        state.value = state.value.copy(blockUnknownCallers = enabled)
    }
    override suspend fun addBlockedPrefix(prefix: String) {
        val trimmed = prefix.trim()
        if (trimmed.isEmpty()) return
        state.value = state.value.copy(blockedPrefixes = state.value.blockedPrefixes + trimmed)
    }
    override suspend fun removeBlockedPrefix(prefix: String) {
        state.value = state.value.copy(blockedPrefixes = state.value.blockedPrefixes - prefix)
    }
    override suspend fun addAllowedNumber(number: String) {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return
        state.value = state.value.copy(allowedNumbers = state.value.allowedNumbers + trimmed)
    }
    override suspend fun removeAllowedNumber(number: String) {
        state.value = state.value.copy(allowedNumbers = state.value.allowedNumbers - number)
    }
    override suspend fun setAllowRepeatCallers(enabled: Boolean) {
        state.value = state.value.copy(allowRepeatCallers = enabled)
    }
    override suspend fun setRepeatCallerWindowMinutes(minutes: Int) {
        state.value = state.value.copy(repeatCallerWindowMinutes = minutes)
    }
    override suspend fun setAlwaysAllowFavorites(enabled: Boolean) {
        state.value = state.value.copy(alwaysAllowFavorites = enabled)
    }
    override suspend fun setBlockingScheduleEnabled(enabled: Boolean) {
        state.value = state.value.copy(blockingScheduleEnabled = enabled)
    }
    override suspend fun setBlockingSchedule(startMinutes: Int, endMinutes: Int) {
        state.value = state.value.copy(
            blockingScheduleStartMinutes = startMinutes,
            blockingScheduleEndMinutes = endMinutes,
        )
    }
}

class FakeSimRepository(var sims: List<SimCard> = emptyList()) : SimRepository {
    override suspend fun activeSims(): List<SimCard> = sims
}

class FakeSpeedDialRepository : SpeedDialRepository {
    val state = MutableStateFlow<Map<Int, String>>(emptyMap())
    override val entries: Flow<Map<Int, String>> = state
    override suspend fun set(digit: Int, number: String) {
        state.value = state.value + (digit to number)
    }
    override suspend fun clear(digit: Int) {
        state.value = state.value - digit
    }
}

class FakePermissionChecker(private val granted: MutableSet<String> = mutableSetOf()) : PermissionChecker {
    fun grant(permission: String) = granted.add(permission)
    override fun has(permission: String): Boolean = permission in granted
}
