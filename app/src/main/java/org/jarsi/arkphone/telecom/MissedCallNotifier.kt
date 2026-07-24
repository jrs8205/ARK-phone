package org.jarsi.arkphone.telecom

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.MainActivity
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the app's missed-call notification. The system suppresses its own once
 * the default dialer declares a receiver for the missed-call broadcast, so
 * this is the only surface the user sees; its channel badges the launcher icon.
 */
@Singleton
class MissedCallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactsRepository: ContactsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        const val CHANNEL_MISSED = "missed_calls"
        const val NOTIFICATION_ID = 2
        const val ACTION_CALL_BACK = "org.jarsi.arkphone.action.CALL_BACK"
        const val EXTRA_NUMBER = "org.jarsi.arkphone.extra.NUMBER"
    }

    fun onMissedCallsChanged(count: Int, number: String?, onDone: () -> Unit = {}) {
        if (count <= 0) {
            cancelNotification()
            onDone()
            return
        }
        scope.launch {
            val match = number?.let { contactsRepository.lookupContact(it) }
            val largeIcon = match?.photoUri?.let { loadBitmap(it) }
            postNotification(count, number, match?.displayName, largeIcon)
            onDone()
        }
    }

    /** Clears the notification and resets the system's missed-call counter. */
    // Lint only knows the MODIFY_PHONE_STATE route; the default dialer may call
    // cancelMissedCallsNotification without it, and runCatching guards the rest.
    @SuppressLint("MissingPermission")
    fun onCallLogSeen() {
        cancelNotification()
        runCatching {
            context.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification()
        }
    }

    private fun postNotification(count: Int, number: String?, displayName: String?, largeIcon: Bitmap?) {
        ensureChannel()
        val caller = displayName?.takeIf { it.isNotBlank() }
            ?: number
            ?: context.getString(R.string.incall_unknown_caller)
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(
                context.resources.getQuantityString(
                    R.plurals.notification_missed_calls_title, count, count,
                ),
            )
            .setContentText(caller)
            .setLargeIcon(largeIcon)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(openApp)
        if (number != null) {
            builder.addAction(
                0,
                context.getString(R.string.notification_missed_call_back),
                callBackIntent(number),
            )
        }
        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun cancelNotification() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_MISSED,
            context.getString(R.string.notification_channel_missed),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun callBackIntent(number: String): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(ACTION_CALL_BACK)
            .putExtra(EXTRA_NUMBER, number)
        return PendingIntent.getBroadcast(
            context, ACTION_CALL_BACK.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private suspend fun loadBitmap(uriString: String): Bitmap? = withContext(ioDispatcher) {
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))
                ?.use(BitmapFactory::decodeStream)
        }.getOrNull()
    }
}
