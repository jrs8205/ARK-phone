package org.jarsi.arkphone.ui.conversation

import android.content.Intent
import android.net.Uri
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
import org.jarsi.arkphone.messaging.MessageNotifier
import org.jarsi.arkphone.messaging.MessageSharer
import org.jarsi.arkphone.messaging.MmsSender
import org.jarsi.arkphone.messaging.SmsRole
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

/** Sms and mms rows live in different tables whose ids overlap; a
 *  selection key carries the table side too. */
data class MessageKey(val id: Long, val isMms: Boolean)

internal val Message.selectionKey: MessageKey get() = MessageKey(id, isMms)

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
    /** The picked image waiting in the composer, as a content URI string. */
    val attachedImageUri: String? = null,
    /** False while ARK-phone is not the default SMS app. */
    val canSend: Boolean = true,
    val selectedMessageKeys: Set<MessageKey> = emptySet(),
) {
    val rows: List<ConversationRow> get() = dateSeparators(messages)
    val selectionActive: Boolean get() = selectedMessageKeys.isNotEmpty()
    val selectedMessages: List<Message>
        get() = messages.filter { it.selectionKey in selectedMessageKeys }
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val messagesRepository: MessagesRepository,
    private val contactsRepository: ContactsRepository,
    private val blockedNumbersRepository: BlockedNumbersRepository,
    private val smsSender: SmsSender,
    private val mmsSender: MmsSender,
    private val smsRole: SmsRole,
    private val messageSharer: MessageSharer,
    private val messageNotifier: MessageNotifier,
) : ViewModel() {

    private val threadId = MutableStateFlow<Long?>(null)
    private val contact = MutableStateFlow<ContactMatch?>(null)
    private val blocked = MutableStateFlow(false)
    private val canBlock = MutableStateFlow(false)
    private val attachedImage = MutableStateFlow<Uri?>(null)
    private val canSend = MutableStateFlow(smsRole.isHeld())
    private val recipients = MutableStateFlow<List<String>>(emptyList())
    private val selectedKeys = MutableStateFlow<Set<MessageKey>>(emptySet())

    private data class SendPanel(
        val blocked: Boolean,
        val canBlock: Boolean,
        val attachedImage: Uri?,
        val canSend: Boolean,
        val selection: Set<MessageKey>,
    )

    private val blockingState = combine(blocked, canBlock) { isBlocked, blockingAvailable ->
        isBlocked to blockingAvailable
    }

    private val sendPanel = combine(blockingState, attachedImage, canSend, selectedKeys) {
            (isBlocked, blockingAvailable), attachment, sendAllowed, selection ->
        SendPanel(isBlocked, blockingAvailable, attachment, sendAllowed, selection)
    }

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
        sendPanel,
        recipients,
    ) { messages, contacts, match, panel, threadRecipients ->
        // The threads table is the authority on who the conversation is
        // with; message rows only stand in while it has not loaded — sent
        // MMS rows carry no address at all.
        val addresses = threadRecipients.ifEmpty {
            messages.map { it.address }.filter { it.isNotBlank() }.distinct()
        }
        ConversationUiState(
            loading = false,
            messages = messages,
            title = match?.displayName ?: conversationTitle(addresses, contacts),
            photoUri = match?.photoUri,
            contactId = match?.contactId,
            address = addresses.singleOrNull(),
            isGroup = addresses.size > 1,
            blocked = panel.blocked,
            canBlock = panel.canBlock,
            attachedImageUri = panel.attachedImage?.toString(),
            canSend = panel.canSend,
            selectedMessageKeys = panel.selection.filterTo(mutableSetOf()) { key ->
                messages.any { it.selectionKey == key }
            },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConversationUiState(),
    )

    fun open(threadId: Long) {
        if (this.threadId.value == threadId) return
        this.threadId.value = threadId
        canSend.value = smsRole.isHeld()
        viewModelScope.launch {
            recipients.value = messagesRepository.recipients(threadId)
            messagesRepository.markThreadRead(threadId)
            messageNotifier.cancelThread(threadId)
            canBlock.value = blockedNumbersRepository.canBlock()
        }
    }

    // Called by the screen whenever the visible message list changes, so a
    // message arriving while the user is reading the thread is also marked
    // read and its notification cleared — open() alone only covers entry.
    fun onMessagesViewed() {
        val id = threadId.value ?: return
        viewModelScope.launch {
            messagesRepository.markThreadRead(id)
            messageNotifier.cancelThread(id)
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

    fun onAttachImage(imageUri: Uri?) {
        attachedImage.value = imageUri
    }

    fun currentAttachment(): Uri? = attachedImage.value

    fun onSendText(body: String) {
        if (!canSend.value) return
        val state = uiState.value
        val trimmed = body.trim()
        val attachment = attachedImage.value
        if (trimmed.isEmpty() && attachment == null) return
        val groupRecipients = recipients.value.takeIf { it.size > 1 }
        val address = state.address
        if (groupRecipients == null && address == null) return
        viewModelScope.launch {
            when {
                // A group reply is a group MMS even when it is only text:
                // that is what keeps everyone's copy in the same thread.
                groupRecipients != null -> {
                    attachedImage.value = null
                    mmsSender.send(groupRecipients, trimmed.takeIf { it.isNotEmpty() }, attachment)
                }
                attachment != null -> {
                    attachedImage.value = null
                    mmsSender.send(listOf(address!!), trimmed.takeIf { it.isNotEmpty() }, attachment)
                }
                else -> smsSender.send(address!!, trimmed)
            }
            messagesRepository.refresh()
        }
    }

    fun onRetry(message: Message) {
        if (!canSend.value) return
        // The retry path deletes and resends through the SMS sender; an MMS
        // row must never reach it — the ids point at a different table.
        if (message.isMms || message.incoming || message.status != MessageStatus.FAILED) return
        val body = message.body ?: return
        viewModelScope.launch {
            smsSender.discardFailed(message.id)
            smsSender.send(message.address, body)
            messagesRepository.refresh()
        }
    }

    fun onToggleMessageSelection(message: Message) {
        val key = message.selectionKey
        selectedKeys.value = selectedKeys.value.let { if (key in it) it - key else it + key }
    }

    fun onClearSelection() {
        selectedKeys.value = emptySet()
    }

    fun onDeleteSelected() {
        val targets = selectedKeys.value
        if (targets.isEmpty()) return
        viewModelScope.launch {
            targets.forEach { messagesRepository.deleteMessage(it.id, it.isMms) }
            selectedKeys.value = emptySet()
            messagesRepository.refresh()
        }
    }

    fun onShareSelected(onReady: (Intent) -> Unit) {
        val targets = uiState.value.selectedMessages
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val intent = messageSharer.shareIntent(targets) ?: return@launch
            selectedKeys.value = emptySet()
            onReady(intent)
        }
    }

    fun onDeleteConversation(onDeleted: () -> Unit) {
        val target = threadId.value ?: return
        viewModelScope.launch {
            if (messagesRepository.deleteThread(target)) onDeleted()
        }
    }
}
