package org.jarsi.arkphone.voip

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallNotifications
import javax.inject.Inject

/** Keeps the mic and the WebRTC connection alive while an ARK call is active. */
@AndroidEntryPoint
class VoipForegroundService : Service() {

    @Inject lateinit var callNotifications: CallNotifications

    @Inject lateinit var callController: CallController

    override fun onCreate() {
        super.onCreate()
        // The service's own channel and notification are gone: the ongoing
        // notification IS the foreground notice now. Two entries under the
        // same app left the user guessing which one returns to the call.
        getSystemService(NotificationManager::class.java)
            .deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val info = callController.calls.value.firstOrNull()
        val notification = callNotifications.buildOngoingCall(info)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                CallNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(CallNotifications.NOTIFICATION_ID, notification)
        }
        // Telecom owns routing for a registered call, but the communication
        // mode is what makes the earpiece and the mic behave like a call.
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val LEGACY_CHANNEL_ID = "voip_calls"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, VoipForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoipForegroundService::class.java))
        }
    }
}
