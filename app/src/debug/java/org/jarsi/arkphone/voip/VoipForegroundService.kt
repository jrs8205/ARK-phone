package org.jarsi.arkphone.voip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import org.jarsi.arkphone.R

/** Keeps the mic and the WebRTC connection alive while a test call is active. */
class VoipForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voip_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle(getString(R.string.voip_notification_title))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "voip_test"
        private const val NOTIFICATION_ID = 4001

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoipForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoipForegroundService::class.java))
        }
    }
}
