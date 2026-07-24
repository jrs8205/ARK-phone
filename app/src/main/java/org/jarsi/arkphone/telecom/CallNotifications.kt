package org.jarsi.arkphone.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.incall.InCallActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val CHANNEL_INCOMING = "incoming_calls"
        const val CHANNEL_INCOMING_SILENT = "incoming_calls_silent"
        const val CHANNEL_ONGOING = "ongoing_calls"
        const val NOTIFICATION_ID = 1
        const val ACTION_ANSWER = "org.jarsi.arkphone.action.ANSWER"
        const val ACTION_DECLINE = "org.jarsi.arkphone.action.DECLINE"
        const val EXTRA_CALL_ID = "org.jarsi.arkphone.extra.CALL_ID"
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val incoming = NotificationChannel(
            CHANNEL_INCOMING,
            context.getString(R.string.notification_channel_incoming),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(ringtone, audioAttributes)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // Voice-only announcement mode: the ringtone comes from the incoming
        // channel, so ringing silently means posting on this channel instead.
        val incomingSilent = NotificationChannel(
            CHANNEL_INCOMING_SILENT,
            context.getString(R.string.notification_channel_incoming_silent),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(null, null)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.notification_channel_ongoing),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(incoming)
        notificationManager.createNotificationChannel(incomingSilent)
        notificationManager.createNotificationChannel(ongoing)
    }

    fun showIncomingCall(info: CallInfo, silentRing: Boolean = false) {
        notify(buildIncomingCall(info, silentRing))
    }

    internal fun buildIncomingCall(info: CallInfo, silentRing: Boolean = false): Notification {
        val fullScreen = PendingIntent.getActivity(
            context, 0, InCallActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = Person.Builder()
            .setName(info.displayName ?: info.number ?: context.getString(R.string.incall_unknown_caller))
            .setImportant(true)
            .build()
        val channel = if (silentRing) CHANNEL_INCOMING_SILENT else CHANNEL_INCOMING
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(caller.name)
            .setContentText(context.getString(R.string.notification_incoming_title))
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    declineIntent(info.id),
                    answerIntent(info.id),
                ),
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
    }

    fun showOngoingCall(info: CallInfo) {
        val content = PendingIntent.getActivity(
            context, 0, InCallActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notification_ongoing_title))
            .setContentText(info.displayName ?: info.number ?: context.getString(R.string.incall_unknown_caller))
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(content)
            .build()
        notify(notification)
    }

    fun clear() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun declineIntent(callId: String): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(ACTION_DECLINE)
            .putExtra(EXTRA_CALL_ID, callId)
        return PendingIntent.getBroadcast(
            context, ACTION_DECLINE.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Answering opens the in-call screen directly; the activity performs the answer. */
    private fun answerIntent(callId: String): PendingIntent {
        val intent = InCallActivity.intent(context)
            .setAction(ACTION_ANSWER)
            .putExtra(EXTRA_CALL_ID, callId)
        return PendingIntent.getActivity(
            context, ACTION_ANSWER.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notify(notification: Notification) {
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }
}
