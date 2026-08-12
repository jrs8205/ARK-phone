package org.jarsi.arkphone.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.MessagesRepository
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject

/** What one notification-action tap does, testable without a receiver. */
class MessageActionHandler @Inject constructor(
    private val smsSender: SmsSender,
    private val mmsSender: MmsSender,
    private val messagesRepository: MessagesRepository,
    private val messageNotifier: MessageNotifier,
) {

    suspend fun handle(
        action: String?,
        threadId: Long,
        replyText: String?,
        subscriptionId: Int = -1,
    ) {
        if (action != MessageActionReceiver.ACTION_MESSAGE_REPLY &&
            action != MessageActionReceiver.ACTION_MESSAGE_MARK_READ
        ) {
            return
        }
        if (threadId < 0) return
        if (action == MessageActionReceiver.ACTION_MESSAGE_REPLY && !replyText.isNullOrBlank()) {
            val recipients =
                runCatching { messagesRepository.recipients(threadId) }.getOrDefault(emptyList())
            when {
                // A group reply is a group MMS even when it is only text — an
                // SMS to one member would fork the thread into a 1:1
                // conversation (same rule as the in-app reply).
                recipients.size > 1 ->
                    mmsSender.send(recipients, replyText.trim(), null, subscriptionId)
                recipients.size == 1 -> smsSender.send(recipients.single(), replyText.trim())
                // The membership lookup failed: a blind SMS to the sender
                // forks a group into a 1:1 thread, and clearing the
                // notification would pass the swallowed reply off as sent.
                else -> return
            }
        }
        messagesRepository.markThreadRead(threadId)
        messageNotifier.cancelThread(threadId)
        messagesRepository.refresh()
    }
}

/** The text the user typed into the notification's inline reply, if any. */
internal fun replyTextFrom(intent: Intent): String? =
    RemoteInput.getResultsFromIntent(intent)
        ?.getCharSequence(MessageActionReceiver.KEY_REPLY_TEXT)
        ?.toString()

/** Handles the message notification's inline reply and mark-read taps. */
@AndroidEntryPoint
class MessageActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_MESSAGE_REPLY = "org.jarsi.arkphone.action.MESSAGE_REPLY"
        const val ACTION_MESSAGE_MARK_READ = "org.jarsi.arkphone.action.MESSAGE_MARK_READ"
        const val EXTRA_THREAD_ID = "org.jarsi.arkphone.extra.THREAD_ID"
        const val EXTRA_SUBSCRIPTION_ID = "org.jarsi.arkphone.extra.SUBSCRIPTION_ID"
        const val KEY_REPLY_TEXT = "org.jarsi.arkphone.extra.REPLY_TEXT"
    }

    @Inject lateinit var handler: MessageActionHandler

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                handler.handle(
                    action = intent.action,
                    threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L),
                    replyText = replyTextFrom(intent),
                    subscriptionId = intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, -1),
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
