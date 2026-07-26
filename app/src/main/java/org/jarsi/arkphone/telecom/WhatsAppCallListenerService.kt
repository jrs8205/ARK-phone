package org.jarsi.arkphone.telecom

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val EXTRA_CALL_TYPE = "android.callType"

@AndroidEntryPoint
class WhatsAppCallListenerService : NotificationListenerService() {

    private companion object {
        const val TAG = "ArkPhone"
    }

    @Inject lateinit var callerAnnouncer: CallerAnnouncer

    @Inject lateinit var callMonitor: WhatsAppCallMonitor

    override fun onListenerConnected() {
        Log.i(TAG, "WhatsApp listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        logWhatsAppCallNotification("posted", sbn)
        // Signal-less transitional updates return null and must fall through
        // silently: on the lock screen they arrive while the call still rings.
        val kind = classifyWhatsAppCallNotification(sbn.packageName, sbn.notification, sbn.tag)
            ?: return
        val caller = whatsAppCaller(sbn.notification).copy(sourcePackage = sbn.packageName)
        val established =
            sbn.notification.extras.getInt(EXTRA_CALL_TYPE, -1) == 2 ||
                sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        // The monitor knows whether a ringing-shaped update really is a new
        // ring or just WhatsApp refreshing the one notification it keeps
        // reusing mid-call — announce only true rings, silence the rest.
        val effective = callMonitor.onCallNotificationPosted(sbn.key, kind, caller, established)
        if (effective == WhatsAppCallNotificationKind.RINGING) {
            Log.i(TAG, "WhatsApp incoming call detected, announcing")
            callerAnnouncer.onWhatsAppRinging(sbn.key, caller.callerName, caller.callerNumber)
        } else {
            callerAnnouncer.onWhatsAppRingingStopped(sbn.key)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        logWhatsAppCallNotification("removed", sbn)
        callMonitor.onCallNotificationRemoved(sbn.key)
        callerAnnouncer.onWhatsAppRingingStopped(sbn.key)
    }

    private fun logWhatsAppCallNotification(event: String, sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (!sbn.packageName.startsWith("com.whatsapp") ||
            notification.category != Notification.CATEGORY_CALL
        ) {
            return
        }
        Log.i(
            TAG,
            "WhatsApp call notification $event: callType=" +
                notification.extras.getInt(EXTRA_CALL_TYPE, -1) +
                " fullScreen=" + (notification.fullScreenIntent != null) +
                " chronometer=" +
                notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) +
                " tag=" + sbn.tag +
                " extras=" + notification.extras.keySet().joinToString(","),
        )
    }
}
