package org.jarsi.arkphone.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.MainActivity
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.util.sameCaller
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A quiet trace of rule-blocked calls: a silent low-importance notification
 * ("1 blocked call") the user notices later, never a sound or a banner. Also
 * remembers recent blocks so the missed-call notification can be suppressed
 * when a silently blocked call rings out to voicemail.
 */
@Singleton
class BlockedCallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactsRepository: ContactsRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    companion object {
        const val CHANNEL_BLOCKED = "blocked_calls"
        const val NOTIFICATION_ID = 3
        const val ACTION_BLOCKED_DISMISSED = "org.jarsi.arkphone.action.BLOCKED_DISMISSED"
        const val RECENT_WINDOW_MILLIS = 5 * 60_000L
    }

    private val lock = Any()
    private val recentBlocks = mutableListOf<Pair<String?, Long>>()
    private var unseenCount = 0

    fun onCallBlocked(number: String?) {
        val count = synchronized(lock) {
            recentBlocks += number to clock.nowMillis()
            ++unseenCount
        }
        scope.launch {
            val name = number?.let { contactsRepository.lookupContact(it)?.displayName }
            postNotification(count, name ?: number)
        }
    }

    /** Opening the app acknowledges the blocked calls, like the missed ones. */
    fun onSeen() {
        synchronized(lock) { unseenCount = 0 }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun wasRecentlyBlocked(number: String?): Boolean = synchronized(lock) {
        val cutoff = clock.nowMillis() - RECENT_WINDOW_MILLIS
        recentBlocks.removeAll { (_, at) -> at < cutoff }
        recentBlocks.any { (blocked, _) ->
            if (number.isNullOrBlank()) {
                blocked.isNullOrBlank()
            } else {
                !blocked.isNullOrBlank() && sameCaller(blocked, number)
            }
        }
    }

    private fun postNotification(count: Int, caller: String?) {
        ensureChannel()
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_BLOCKED)
            .setSmallIcon(R.drawable.ic_notification_call)
            .setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.notification_blocked_calls_title, count, count,
                ),
            )
            .setContentText(caller ?: context.getString(R.string.incall_unknown_caller))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setNumber(count)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setDeleteIntent(dismissIntent())
            .build()
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun dismissIntent(): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(ACTION_BLOCKED_DISMISSED)
        return PendingIntent.getBroadcast(
            context, ACTION_BLOCKED_DISMISSED.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_BLOCKED,
            context.getString(R.string.notification_channel_blocked),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
