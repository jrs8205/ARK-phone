package org.jarsi.arkphone.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.MainActivity
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.conversation.ConversationActivity
import javax.inject.Inject
import javax.inject.Singleton

/** Posts and clears the per-thread new-message notifications. */
interface MessageNotifier {
    fun notifyMessage(
        threadId: Long,
        address: String,
        displayName: String?,
        body: String,
        timestampMillis: Long,
        subscriptionId: Int = -1,
    )

    fun cancelThread(threadId: Long)
}

@Singleton
class AndroidMessageNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : MessageNotifier {

    companion object {
        const val CHANNEL_MESSAGES = "messages"

        fun notificationIdFor(threadId: Long): Int = (10_000 + threadId % 10_000).toInt()
    }

    override fun notifyMessage(
        threadId: Long,
        address: String,
        displayName: String?,
        body: String,
        timestampMillis: Long,
        subscriptionId: Int,
    ) {
        ensureChannel()
        val self = Person.Builder()
            .setName(context.getString(R.string.message_notification_self))
            .build()
        val sender = Person.Builder()
            .setName(displayName?.takeIf { it.isNotBlank() } ?: address)
            .build()
        val style = NotificationCompat.MessagingStyle(self)
            .addMessage(body, timestampMillis, sender)
        val builder = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openThreadIntent(threadId))
            .addAction(replyAction(threadId, address, subscriptionId))
            .addAction(markReadAction(threadId, address))
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context)
                .notify(notificationIdFor(threadId), builder.build())
        }
    }

    override fun cancelThread(threadId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationIdFor(threadId))
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun replyAction(
        threadId: Long,
        address: String,
        subscriptionId: Int,
    ): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(MessageActionReceiver.KEY_REPLY_TEXT)
            .setLabel(context.getString(R.string.message_reply))
            .build()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "reply-$threadId".hashCode(),
            actionIntent(MessageActionReceiver.ACTION_MESSAGE_REPLY, threadId, address)
                // The reply must leave over the SIM the message arrived on;
                // the default messaging SIM can be the other one.
                .putExtra(MessageActionReceiver.EXTRA_SUBSCRIPTION_ID, subscriptionId),
            // Mutability is what lets the system attach the typed reply.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.message_reply),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
    }

    private fun markReadAction(threadId: Long, address: String): NotificationCompat.Action {
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            "mark-read-$threadId".hashCode(),
            actionIntent(MessageActionReceiver.ACTION_MESSAGE_MARK_READ, threadId, address),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(
            0,
            context.getString(R.string.message_mark_read),
            pendingIntent,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
    }

    private fun actionIntent(action: String, threadId: Long, address: String): Intent =
        Intent(context, MessageActionReceiver::class.java)
            .setAction(action)
            .putExtra(MessageActionReceiver.EXTRA_THREAD_ID, threadId)
            .putExtra(MessageActionReceiver.EXTRA_ADDRESS, address)

    /** Back from the opened thread lands on the app's main screen. */
    private fun openThreadIntent(threadId: Long): PendingIntent =
        TaskStackBuilder.create(context)
            .addNextIntent(Intent(context, MainActivity::class.java))
            .addNextIntent(ConversationActivity.intent(context, threadId))
            .getPendingIntent(
                notificationIdFor(threadId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )!!
}
