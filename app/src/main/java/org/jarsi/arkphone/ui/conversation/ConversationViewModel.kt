package org.jarsi.arkphone.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.MessagesRepository
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.messaging.SmsSender
import org.jarsi.arkphone.ui.messages.conversationTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** One rendered row of the thread: a message bubble, or the day header
 *  above the first message of that day. */
sealed interface ConversationRow {
    data class DaySeparator(val epochMillis: Long) : ConversationRow
    data class MessageRow(val message: Message) : ConversationRow
}

internal fun dateSeparators(messages: List<Message>): List<ConversationRow> {
    val rows = mutableListOf<ConversationRow>()
    var previousDay: LocalDate? = null
    messages.forEach { message ->
        val day = Instant.ofEpochMilli(message.timestampMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        if (day != previousDay) {
            rows += ConversationRow.DaySeparator(message.timestampMillis)
            previousDay = day
        }
        rows += ConversationRow.MessageRow(message)
    }
    return rows
}

data class ConversationUiState(
    val loading: Boolean = true,
    val messages: List<Message> = emptyList(),
    val title: String = "",
    val photoUri: String? = null,
    val contactId: Long? = null,
    /** The single other party; null for group threads. */
    val address: String? = null,
    val isGroup: Boolean = false,
    val blocked: Boolean = false,
    val canBlock: Boolean = false,
) {
    val rows: List<ConversationRow> get() = dateSeparators(messages)
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val messagesRepository: MessagesRepository,
    private val contactsRepository: ContactsRepository,
    private val blockedNumbersRepository: BlockedNumbersRepository,
    private val smsSender: SmsSender,
) : ViewModel() {

    /** The SIM the next send uses; Task 8 wires the per-conversation choice. */
    private var selectedSubscriptionId: Int = -1

    private val threadId = MutableStateFlow<Long?>(null)
    private val contact = MutableStateFlow<ContactMatch?>(null)
    private val blocked = MutableStateFlow(false)
    private val canBlock = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val messages = threadId.filterNotNull()
        .flatMapLatest(messagesRepository::messages)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    init {
        viewModelScope.launch {
            messages
                .map { list -> list.map { it.address }.filter { it.isNotBlank() }.distinct().singleOrNull() }
                .distinctUntilChanged()
                .collect { single ->
                    contact.value = single?.let { contactsRepository.lookupContact(it) }
                    blocked.value = single != null && blockedNumbersRepository.isBlocked(single)
                }
        }
    }

    val uiState: StateFlow<ConversationUiState> = combine(
        messages,
        contactsRepository.contacts(),
        contact,
        blocked,
        canBlock,
    ) { messages, contacts, match, isBlocked, blockingAvailable ->
        val addresses = messages.map { it.address }.filter { it.isNotBlank() }.distinct()
        ConversationUiState(
            loading = false,
            messages = messages,
            title = match?.displayName ?: conversationTitle(addresses, contacts),
            photoUri = match?.photoUri,
            contactId = match?.contactId,
            address = addresses.singleOrNull(),
            isGroup = addresses.size > 1,
            blocked = isBlocked,
            canBlock = blockingAvailable,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConversationUiState(),
    )

    fun open(threadId: Long) {
        if (this.threadId.value == threadId) return
        this.threadId.value = threadId
        viewModelScope.launch {
            messagesRepository.markThreadRead(threadId)
            canBlock.value = blockedNumbersRepository.canBlock()
        }
    }

    fun onToggleBlocked() {
        val target = uiState.value.address ?: return
        viewModelScope.launch {
            if (blocked.value) {
                if (blockedNumbersRepository.unblock(target)) blocked.value = false
            } else {
                if (blockedNumbersRepository.block(target)) blocked.value = true
            }
        }
    }

    fun onSendText(body: String) {
        val address = uiState.value.address ?: return
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            smsSender.send(address, trimmed, selectedSubscriptionId)
            messagesRepository.refresh()
        }
    }

    fun onRetry(message: Message) {
        if (message.incoming || message.status != MessageStatus.FAILED) return
        val body = message.body ?: return
        viewModelScope.launch {
            smsSender.discardFailed(message.id)
            smsSender.send(message.address, body, message.subscriptionId)
            messagesRepository.refresh()
        }
    }

    fun onDeleteConversation(onDeleted: () -> Unit) {
        val target = threadId.value ?: return
        viewModelScope.launch {
            if (messagesRepository.deleteThread(target)) onDeleted()
        }
    }
}
