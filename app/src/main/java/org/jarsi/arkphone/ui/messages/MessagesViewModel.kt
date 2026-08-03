package org.jarsi.arkphone.ui.messages

import android.Manifest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.MessagesRepository
import org.jarsi.arkphone.data.model.Contact
import org.jarsi.arkphone.data.model.Conversation
import org.jarsi.arkphone.messaging.SmsRole
import org.jarsi.arkphone.util.PermissionChecker
import org.jarsi.arkphone.util.sameCaller
import javax.inject.Inject

/** One conversation row with its display identity resolved from contacts. */
data class ConversationItem(
    val conversation: Conversation,
    val title: String,
    val photoUri: String?,
    val isGroup: Boolean,
)

data class MessagesUiState(
    val loading: Boolean = true,
    val conversations: List<ConversationItem> = emptyList(),
    val query: String = "",
    val hasReadSmsPermission: Boolean = true,
    val isDefaultSmsApp: Boolean = true,
    val selectedThreadIds: Set<Long> = emptySet(),
) {
    val selectionActive: Boolean get() = selectedThreadIds.isNotEmpty()
}

/** Contact names for every participant, the raw address when unknown. */
internal fun conversationTitle(addresses: List<String>, contacts: List<Contact>): String =
    addresses.joinToString(", ") { address ->
        contacts.firstOrNull { it.phoneNumber != null && sameCaller(it.phoneNumber, address) }
            ?.displayName ?: address
    }

internal fun matchesConversationQuery(item: ConversationItem, query: String): Boolean {
    if (query.isBlank()) return true
    if (item.title.contains(query, ignoreCase = true)) return true
    val queryDigits = query.filter { it.isDigit() }
    if (queryDigits.isEmpty()) return false
    return item.conversation.addresses.any { address ->
        val numberDigits = address.filter { it.isDigit() }
        // "0445..." must also find "+358 44 5..." — retry without the trunk
        // zero, but never with the empty rest of a lone "0".
        numberDigits.contains(queryDigits) ||
            (
                queryDigits.length > 1 && queryDigits.startsWith("0") &&
                    numberDigits.contains(queryDigits.drop(1))
                )
    }
}

private fun singleContactPhoto(addresses: List<String>, contacts: List<Contact>): String? {
    val address = addresses.singleOrNull() ?: return null
    return contacts.firstOrNull { it.phoneNumber != null && sameCaller(it.phoneNumber, address) }
        ?.photoUri
}

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: MessagesRepository,
    contactsRepository: ContactsRepository,
    private val permissionChecker: PermissionChecker,
    private val smsRole: SmsRole,
) : ViewModel() {

    private val permissionState = MutableStateFlow(hasSmsPermission())
    private val roleState = MutableStateFlow(smsRole.isHeld())
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<Long>>(emptySet())

    private val access = combine(permissionState, roleState, selected) { hasPermission, roleHeld, sel ->
        Triple(hasPermission, roleHeld, sel)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val bodyMatchedThreadIds: Flow<Set<Long>> =
        query.mapLatest { repository.threadIdsMatchingBody(it) }

    val uiState: StateFlow<MessagesUiState> = combine(
        repository.conversations(),
        contactsRepository.contacts(),
        query,
        access,
        bodyMatchedThreadIds,
    ) { conversations, contacts, query, (hasPermission, roleHeld, sel), bodyMatches ->
        val items = conversations.map { conversation ->
            ConversationItem(
                conversation = conversation,
                title = conversationTitle(conversation.addresses, contacts),
                photoUri = singleContactPhoto(conversation.addresses, contacts),
                isGroup = conversation.addresses.size > 1,
            )
        }
        MessagesUiState(
            loading = false,
            conversations = items.filter {
                matchesConversationQuery(it, query) || it.conversation.threadId in bodyMatches
            },
            query = query,
            hasReadSmsPermission = hasPermission,
            isDefaultSmsApp = roleHeld,
            selectedThreadIds = sel.filterTo(mutableSetOf()) { id ->
                conversations.any { it.threadId == id }
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MessagesUiState(
            loading = true,
            hasReadSmsPermission = hasSmsPermission(),
            isDefaultSmsApp = smsRole.isHeld(),
        ),
    )

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun onToggleSelection(threadId: Long) {
        selected.value = selected.value.let { if (threadId in it) it - threadId else it + threadId }
    }

    /** Selects every visible conversation, or clears the selection when
     *  everything is already selected so the icon works as a toggle. */
    fun onSelectAll() {
        val visible = uiState.value.conversations.mapTo(mutableSetOf()) {
            it.conversation.threadId
        }
        selected.value = if (selected.value.containsAll(visible)) emptySet() else visible
    }

    fun onClearSelection() {
        selected.value = emptySet()
    }

    fun onDeleteSelected() {
        val targets = selected.value
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.forEach { repository.deleteThread(it) }
            selected.value = emptySet()
            repository.refresh()
        }
    }

    fun refreshPermissionState() {
        permissionState.value = hasSmsPermission()
        roleState.value = smsRole.isHeld()
        repository.refresh()
    }

    private fun hasSmsPermission() = permissionChecker.has(Manifest.permission.READ_SMS)
}
