package org.jarsi.arkphone.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openThreadIntent(threadId))
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
