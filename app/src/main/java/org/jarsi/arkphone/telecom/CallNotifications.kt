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
    // Provider breaks the cycle: the VoIP side depends on this class for its
    // notifications, and this class only needs a build-variant yes/no.
    private val voipCallGateway: javax.inject.Provider<java.util.Optional<org.jarsi.arkphone.voip.VoipCallGateway>>,
) {
    companion object {
        // Bumped ids: a channel's importance is fixed once created, and these
        // dropped from HIGH to DEFAULT to stop the ringing notification from
        // bannering answer/decline buttons over ARK-phone's own call screen.
        const val CHANNEL_INCOMING = "incoming_calls_v2"
        const val CHANNEL_INCOMING_ARK = "incoming_calls_ark"
        const val CHANNEL_INCOMING_ARK_SILENT = "incoming_calls_ark_silent"
        const val CHANNEL_INCOMING_SILENT = "incoming_calls_silent_v2"
        private val REPLACED_CHANNELS = listOf("incoming_calls", "incoming_calls_silent")
        const val CHANNEL_INCOMING_SILENCED = "incoming_calls_silenced"
        const val CHANNEL_ONGOING = "ongoing_calls"
        const val NOTIFICATION_ID = 1
        const val ACTION_ANSWER = "org.jarsi.arkphone.action.ANSWER"
        const val ACTION_DECLINE = "org.jarsi.arkphone.action.DECLINE"
        const val EXTRA_CALL_ID = "org.jarsi.arkphone.extra.CALL_ID"
    }

    private val notificationManager =
        context.getSystemService(NotificationManager::class.java)

    /** False when the user has turned off notifications or an incoming-call
     *  channel: the incoming notification (and its full-screen intent) would
     *  never appear, so the call UI must be launched directly instead. */
    fun incomingNotificationsUsable(): Boolean {
        val manager = notificationManager ?: return false
        if (!manager.areNotificationsEnabled()) return false
        return listOf(CHANNEL_INCOMING, CHANNEL_INCOMING_SILENT).all { id ->
            manager.getNotificationChannel(id)?.importance != NotificationManager.IMPORTANCE_NONE
        }
    }

    private fun screenLockedOrOff(): Boolean {
        val interactive =
            context.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        val keyguardLocked =
            context.getSystemService(android.app.KeyguardManager::class.java)
                ?.isKeyguardLocked == true
        return !interactive || keyguardLocked
    }

    /**
     * API 34+ gates full-screen intents behind a special app op that a
     * sideloaded build starts WITHOUT. When this is false, the ARK incoming
     * notification still shows, but a locked screen will not light up — the
     * settings screen points the user at the grant.
     */
    fun canUseFullScreenIntent(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        return notificationManager?.canUseFullScreenIntent() != false
    }

    fun ensureChannels() {
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val incoming = NotificationChannel(
            CHANNEL_INCOMING,
            context.getString(R.string.notification_channel_incoming),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(ringtone, audioAttributes)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // ARK internet calls ring from a background process, where launching
        // the call screen directly is blocked — their notification carries a
        // full-screen intent instead, and that fires only from a HIGH channel.
        val incomingArk = NotificationChannel(
            CHANNEL_INCOMING_ARK,
            context.getString(R.string.notification_channel_incoming_ark),
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
            NotificationManager.IMPORTANCE_DEFAULT,
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
        // The ARK channels belong only to builds that can receive ARK calls;
        // a release install must neither show nor keep them in settings. The
        // silent variant exists because a voice-only ring still needs HIGH
        // importance for the full-screen intent on a locked screen — just
        // without the channel ringtone under the announcement.
        if (voipCallGateway.get().isPresent) {
            notificationManager.createNotificationChannel(incomingArk)
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_INCOMING_ARK_SILENT,
                    context.getString(R.string.notification_channel_incoming_ark_silent),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        } else {
            notificationManager.deleteNotificationChannel(CHANNEL_INCOMING_ARK)
            notificationManager.deleteNotificationChannel(CHANNEL_INCOMING_ARK_SILENT)
        }
        // User-silenced ring: DEFAULT importance so the re-posted notification
        // stays in the status bar instead of heads-upping over the call screen.
        val incomingSilenced = NotificationChannel(
            CHANNEL_INCOMING_SILENCED,
            context.getString(R.string.notification_channel_incoming_silenced),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
        }
        notificationManager.createNotificationChannel(incomingSilent)
        notificationManager.createNotificationChannel(incomingSilenced)
        notificationManager.createNotificationChannel(ongoing)
        REPLACED_CHANNELS.forEach(notificationManager::deleteNotificationChannel)
    }

    fun showIncomingCall(info: CallInfo, silentRing: Boolean = false, arkBanner: Boolean = false) {
        notify(buildIncomingCall(info, silentRing, arkBanner = arkBanner))
    }

    internal fun buildIncomingCall(
        info: CallInfo,
        silentRing: Boolean = false,
        quiet: Boolean = false,
        arkBanner: Boolean = false,
    ): Notification {
        val openCallScreen = PendingIntent.getActivity(
            context, 0, InCallActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val caller = Person.Builder()
            .setName(info.displayName ?: info.number ?: context.getString(R.string.incall_unknown_caller))
            .setImportant(true)
            .build()
        // The HIGH channel and its full-screen intent exist for the rings
        // where the call screen cannot open itself: a locked or dark screen
        // (the intent lights it) and — [arkBanner] — an awake screen with the
        // app in the background, where activity launches are blocked and the
        // heads-up banner IS the answer surface. With the app in the
        // foreground the call screen opens, and the same channel would stack
        // a second set of answer/decline buttons over it (field-hits
        // 2026-08-09 17:09 and 17:57).
        val arkHighRing = info.viaArkCall && (screenLockedOrOff() || arkBanner)
        val channel = when {
            quiet -> CHANNEL_INCOMING_SILENCED
            arkHighRing && silentRing -> CHANNEL_INCOMING_ARK_SILENT
            arkHighRing -> CHANNEL_INCOMING_ARK
            silentRing -> CHANNEL_INCOMING_SILENT
            else -> CHANNEL_INCOMING
        }
        // No CallStyle, and for carrier calls no full-screen intent either:
        // ARK-phone opens its own call screen for every ringing carrier call,
        // and either would make the system banner a second set of
        // answer/decline buttons over it. A locked-screen ARK internet call
        // is the exception — it rings from a background process where
        // launching the screen is blocked, so its notification carries the
        // full-screen intent that IS the platform's way to ring a locked
        // phone.
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(caller.name)
            .setContentText(context.getString(R.string.notification_incoming_title))
            .apply {
                if (!quiet) {
                    addAction(
                        R.drawable.ic_notification,
                        context.getString(R.string.incall_decline),
                        declineIntent(info.id),
                    )
                    addAction(
                        R.drawable.ic_notification,
                        context.getString(R.string.incall_answer),
                        answerIntent(info.id),
                    )
                }
            }
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(openCallScreen)
            .apply {
                if (arkHighRing && !quiet) setFullScreenIntent(openCallScreen, true)
            }
            .build()
            .apply { if (!quiet) flags = flags or Notification.FLAG_INSISTENT }
    }

    fun showOngoingCall(info: CallInfo) {
        val content = PendingIntent.getActivity(
            context, 0, InCallActivity.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
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

    /** Re-posts the ringing notification quietly: the insistent ringtone dies
     *  with the cancel, and the DEFAULT-importance re-post stays in the status
     *  bar instead of heads-upping duplicate buttons over the call screen. */
    fun silenceRinging(info: CallInfo) {
        clear()
        notify(buildIncomingCall(info, silentRing = true, quiet = true))
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
