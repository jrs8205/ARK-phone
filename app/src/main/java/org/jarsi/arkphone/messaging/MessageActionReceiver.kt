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
    private val messagesRepository: MessagesRepository,
    private val messagingSims: MessagingSims,
    private val messageNotifier: MessageNotifier,
) {

    suspend fun handle(action: String?, threadId: Long, address: String?, replyText: String?) {
        if (action != MessageActionReceiver.ACTION_MESSAGE_REPLY &&
            action != MessageActionReceiver.ACTION_MESSAGE_MARK_READ
        ) {
            return
        }
        if (threadId < 0) return
        if (action == MessageActionReceiver.ACTION_MESSAGE_REPLY &&
            !address.isNullOrBlank() &&
            !replyText.isNullOrBlank()
        ) {
            smsSender.send(address, replyText.trim(), messagingSims.defaultSubscriptionId())
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
        const val EXTRA_ADDRESS = "org.jarsi.arkphone.extra.ADDRESS"
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
                    address = intent.getStringExtra(EXTRA_ADDRESS),
                    replyText = replyTextFrom(intent),
                )
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
