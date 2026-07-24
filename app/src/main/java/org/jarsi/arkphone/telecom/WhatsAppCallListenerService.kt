package org.jarsi.arkphone.telecom

import android.app.Notification
import android.app.Person
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

internal data class WhatsAppCall(val callerName: String?)

private const val EXTRA_CALL_TYPE = "android.callType"
private const val EXTRA_CALL_PERSON = "android.callPerson"
private const val CALL_TYPE_INCOMING = 1

/**
 * Recognizes a ringing WhatsApp call notification, or null for everything
 * else. CallStyle notifications carry the call type; older formats are
 * accepted only with a full-screen intent, which ongoing-call notifications
 * do not set.
 */
internal fun whatsAppIncomingCall(
    packageName: String,
    notification: Notification,
): WhatsAppCall? {
    if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return null
    if (notification.category != Notification.CATEGORY_CALL) return null
    val callType = notification.extras.getInt(EXTRA_CALL_TYPE, -1)
    val incoming = when {
        callType != -1 -> callType == CALL_TYPE_INCOMING
        else -> notification.fullScreenIntent != null
    }
    if (!incoming) return null
    val personName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        BundleCompat.getParcelable(notification.extras, EXTRA_CALL_PERSON, Person::class.java)
            ?.name?.toString()
    } else {
        null
    }
    val name = personName
        ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    return WhatsAppCall(callerName = name?.takeIf { it.isNotBlank() })
}

@AndroidEntryPoint
class WhatsAppCallListenerService : NotificationListenerService() {

    @Inject lateinit var callerAnnouncer: CallerAnnouncer

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val call = whatsAppIncomingCall(sbn.packageName, sbn.notification) ?: return
        callerAnnouncer.onWhatsAppRinging(sbn.key, call.callerName)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        callerAnnouncer.onWhatsAppRingingStopped(sbn.key)
    }
}
