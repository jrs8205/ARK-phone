package org.jarsi.arkphone.testing

import android.telecom.PhoneAccountHandle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.MessagesRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.data.SimAccountRepository
import org.jarsi.arkphone.data.SimRepository
import org.jarsi.arkphone.data.SpeedDialRepository
import org.jarsi.arkphone.data.WhatsAppCallLogRepository
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.BlockedCallAction
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.ContactDetails
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.data.model.Conversation
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.data.model.SimAccount
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
    override suspend fun setBlockedCallAction(action: BlockedCallAction) {
        state.value = state.value.copy(blockedCallAction = action)
    }
    override suspend fun setArkInternetCallsEnabled(enabled: Boolean) {
        state.value = state.value.copy(arkInternetCallsEnabled = enabled)
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
    override suspend fun setCallSimAccountId(accountId: String?) {
        state.value = state.value.copy(callSimAccountId = accountId)
    }
    override suspend fun setBlockingSimAccountId(accountId: String?) {
        state.value = state.value.copy(blockingSimAccountId = accountId)
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
    override suspend fun addBlockedNumber(number: String) {
        val trimmed = number.trim()
        if (trimmed.isEmpty()) return
        state.value = state.value.copy(blockedNumbers = state.value.blockedNumbers + trimmed)
    }
    override suspend fun removeBlockedNumber(number: String) {
        state.value = state.value.copy(blockedNumbers = state.value.blockedNumbers - number)
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

class FakeSimAccountRepository : SimAccountRepository {
    val accounts = mutableListOf<SimAccount>()
    val handles = mutableMapOf<String, PhoneAccountHandle>()
    override suspend fun accounts(): List<SimAccount> = accounts
    override fun handleFor(accountId: String): PhoneAccountHandle? = handles[accountId]
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

class FakeWhatsAppAvailability(var installed: Boolean = true) :
    org.jarsi.arkphone.telecom.WhatsAppAvailability {
    override fun isAnyVariantInstalled(): Boolean = installed
}

class FakeSmsRole(var held: Boolean = false) : org.jarsi.arkphone.messaging.SmsRole {
    override fun isHeld(): Boolean = held
    override fun requestIntent(): android.content.Intent? = null
}

class FakeMessageNotifier : org.jarsi.arkphone.messaging.MessageNotifier {
    data class Notified(
        val threadId: Long,
        val address: String,
        val displayName: String?,
        val body: String,
        val timestampMillis: Long,
        val subscriptionId: Int,
    )
    val notified = mutableListOf<Notified>()
    val cancelledThreads = mutableListOf<Long>()
    override fun notifyMessage(
        threadId: Long,
        address: String,
        displayName: String?,
        body: String,
        timestampMillis: Long,
        subscriptionId: Int,
    ) {
        notified += Notified(threadId, address, displayName, body, timestampMillis, subscriptionId)
    }
    override fun cancelThread(threadId: Long) {
        cancelledThreads += threadId
    }
}

class FakeMmsSender : org.jarsi.arkphone.messaging.MmsSender {
    data class Sent(
        val addresses: List<String>,
        val text: String?,
        val imageUri: android.net.Uri?,
        val subscriptionId: Int,
    )
    val sent = mutableListOf<Sent>()
    override suspend fun send(
        addresses: List<String>,
        text: String?,
        imageUri: android.net.Uri?,
        subscriptionId: Int,
    ): android.net.Uri? {
        sent += Sent(addresses, text, imageUri, subscriptionId)
        return null
    }
}

class FakeMessageSharer : org.jarsi.arkphone.messaging.MessageSharer {
    val requests = mutableListOf<List<Message>>()
    var intent: android.content.Intent? = null
    override suspend fun shareIntent(messages: List<Message>): android.content.Intent? {
        requests += messages
        return intent
    }
}

class FakeSmsSender : org.jarsi.arkphone.messaging.SmsSender {
    val sent = mutableListOf<Pair<String, String>>()
    val discarded = mutableListOf<Long>()
    override suspend fun send(address: String, body: String): android.net.Uri? {
        sent += address to body
        return null
    }
    override suspend fun discardFailed(messageId: Long) {
        discarded += messageId
    }
}

class FakeMessagesRepository : MessagesRepository {
    val conversationsState = MutableStateFlow<List<Conversation>>(emptyList())
    val messagesByThread = mutableMapOf<Long, MutableStateFlow<List<Message>>>()
    val markedRead = mutableListOf<Long>()
    val deletedThreads = mutableListOf<Long>()
    var bodyMatches = mutableMapOf<String, Set<Long>>()
    var refreshCalls = 0
    override fun conversations(): Flow<List<Conversation>> = conversationsState
    override fun messages(threadId: Long): Flow<List<Message>> =
        messagesByThread.getOrPut(threadId) { MutableStateFlow(emptyList()) }
    override fun refresh() {
        refreshCalls++
    }
    var recipientsByThread = mutableMapOf<Long, List<String>>()
    override suspend fun recipients(threadId: Long): List<String> =
        recipientsByThread[threadId].orEmpty()
    override suspend fun markThreadRead(threadId: Long) {
        markedRead += threadId
    }
    val recomputedThreads = mutableListOf<Long>()
    override suspend fun recomputeThreadRead(threadId: Long) {
        recomputedThreads += threadId
    }
    val deletedMessages = mutableListOf<Pair<Long, Boolean>>()
    override suspend fun deleteMessage(messageId: Long, isMms: Boolean): Boolean {
        deletedMessages += messageId to isMms
        return true
    }
    var failingThreadDeletes = setOf<Long>()
    override suspend fun deleteThread(threadId: Long): Boolean {
        if (threadId in failingThreadDeletes) return false
        deletedThreads += threadId
        return true
    }
    override suspend fun threadIdsMatchingBody(query: String): Set<Long> =
        bodyMatches[query] ?: emptySet()
}

class FakeArkIdentityRepository : org.jarsi.arkphone.data.ArkIdentityRepository {
    val state = MutableStateFlow<org.jarsi.arkphone.data.ArkIdentity?>(null)
    val fcmState = MutableStateFlow<String?>(null)
    override val identity: Flow<org.jarsi.arkphone.data.ArkIdentity?> = state
    override suspend fun save(identity: org.jarsi.arkphone.data.ArkIdentity) {
        state.value = identity
    }
    override val syncedFcmToken: Flow<String?> = fcmState
    override suspend fun setSyncedFcmToken(token: String) {
        fcmState.value = token
    }
}

class FakeArkLinkRepository : org.jarsi.arkphone.voip.ArkLinkRepository {
    val state = MutableStateFlow<List<org.jarsi.arkphone.voip.ArkLink>>(emptyList())
    override val links: Flow<List<org.jarsi.arkphone.voip.ArkLink>> = state
    override suspend fun link(
        number: String,
        code: String,
        nickname: String,
        publicKey: String,
        atMillis: Long,
    ) {
        val key = org.jarsi.arkphone.voip.arkLinkKey(number)
        state.value = state.value.filterNot { it.numberKey == key } +
            org.jarsi.arkphone.voip.ArkLink(key, number, code, nickname, publicKey, atMillis)
    }
    override suspend fun unlink(number: String) {
        val key = org.jarsi.arkphone.voip.arkLinkKey(number)
        state.value = state.value.filterNot { it.numberKey == key }
    }
}

class FakeVoipAccountGateway : org.jarsi.arkphone.voip.VoipAccountGateway {
    var registration: org.jarsi.arkphone.voip.ArkRegistration? = null
    var account: org.jarsi.arkphone.voip.ArkAccount? = null
    val registerCalls = mutableListOf<String>()
    val lookUpCalls = mutableListOf<String>()
    override suspend fun register(nickname: String): org.jarsi.arkphone.voip.ArkRegistration? {
        registerCalls += nickname
        return registration
    }
    override suspend fun lookUp(code: String): org.jarsi.arkphone.voip.ArkAccount? {
        lookUpCalls += code
        return account
    }
}

class FakeVoipCallGateway(var accept: Boolean = true) :
    org.jarsi.arkphone.voip.VoipCallGateway {
    val started = mutableListOf<org.jarsi.arkphone.voip.ArkLink>()
    var lastFallback: (() -> Unit)? = null
    var throwOnStart = false
    override fun startCall(
        link: org.jarsi.arkphone.voip.ArkLink,
        onFallbackToCarrier: () -> Unit,
    ): Boolean {
        if (throwOnStart) throw IllegalStateException("engine down")
        started += link
        lastFallback = onFallbackToCarrier
        return accept
    }
}
