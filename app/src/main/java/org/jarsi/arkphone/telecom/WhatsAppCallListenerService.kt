package org.jarsi.arkphone.telecom

import android.app.Notification
import android.app.Person
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.os.BundleCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

internal data class WhatsAppCall(val callerName: String?, val callerNumber: String? = null)

private const val EXTRA_CALL_TYPE = "android.callType"
private const val EXTRA_CALL_PERSON = "android.callPerson"
private const val CALL_TYPE_INCOMING = 1

/** Multi-account WhatsApp titles the ringing notification with the receiving
 *  account as "[ +358 45 ... ]" — the caller is only on the text line. */
private val ACCOUNT_TITLE = Regex("""^\[.*]$""")
private val PHONE_NUMBER = Regex("""\+?\d[\d\s()\-]{5,}\d""")
private val CALLER_SEPARATORS = listOf(" henkilöltä ", " from ")

/**
 * Recognizes a ringing WhatsApp call notification, or null for everything
 * else. CallStyle notifications carry the call type; older formats are
 * accepted with a full-screen intent or a "ringing_call" notification tag —
 * some WhatsApp builds post a plain notification with neither call type nor
 * full-screen intent, and only the tag distinguishes ringing from ongoing.
 */
internal fun whatsAppIncomingCall(
    packageName: String,
    notification: Notification,
    tag: String? = null,
): WhatsAppCall? {
    if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return null
    if (notification.category != Notification.CATEGORY_CALL) return null
    val callType = notification.extras.getInt(EXTRA_CALL_TYPE, -1)
    val incoming = when {
        callType != -1 -> callType == CALL_TYPE_INCOMING
        notification.fullScreenIntent != null -> true
        else -> tag?.contains("ringing_call") == true
    }
    if (!incoming) return null
    val personName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        BundleCompat.getParcelable(notification.extras, EXTRA_CALL_PERSON, Person::class.java)
            ?.name?.toString()
    } else {
        null
    }
    val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        ?.trim()?.takeIf { it.isNotBlank() }
    val name = personName?.takeIf { it.isNotBlank() }
        ?: title?.takeUnless { ACCOUNT_TITLE.matches(it) }
    if (name != null) return WhatsAppCall(callerName = name)
    val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    return text?.let(::callerFromText) ?: WhatsAppCall(callerName = null)
}

/** "Äänipuhelu henkilöltä X" / "Incoming voice call from X" → X, split into
 *  a name or a number so the announcer can resolve numbers against contacts. */
private fun callerFromText(text: String): WhatsAppCall {
    for (separator in CALLER_SEPARATORS) {
        val index = text.lastIndexOf(separator)
        if (index < 0) continue
        val caller = text.substring(index + separator.length).trim().trimEnd('.')
        if (caller.isEmpty()) continue
        return if (PHONE_NUMBER.matches(caller)) {
            WhatsAppCall(callerName = null, callerNumber = caller)
        } else {
            WhatsAppCall(callerName = caller)
        }
    }
    return WhatsAppCall(callerName = null, callerNumber = PHONE_NUMBER.find(text)?.value)
}

@AndroidEntryPoint
class WhatsAppCallListenerService : NotificationListenerService() {

    private companion object {
        const val TAG = "ArkPhone"
    }

    @Inject lateinit var callerAnnouncer: CallerAnnouncer

    override fun onListenerConnected() {
        Log.i(TAG, "WhatsApp listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val notification = sbn.notification
        if (sbn.packageName.startsWith("com.whatsapp") &&
            notification.category == Notification.CATEGORY_CALL
        ) {
            Log.i(
                TAG,
                "WhatsApp call notification: callType=" +
                    notification.extras.getInt(EXTRA_CALL_TYPE, -1) +
                    " fullScreen=" + (notification.fullScreenIntent != null) +
                    " tag=" + sbn.tag +
                    " extras=" + notification.extras.keySet().joinToString(","),
            )
        }
        val call = whatsAppIncomingCall(sbn.packageName, notification, sbn.tag) ?: return
        Log.i(TAG, "WhatsApp incoming call detected, announcing")
        callerAnnouncer.onWhatsAppRinging(sbn.key, call.callerName, call.callerNumber)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        callerAnnouncer.onWhatsAppRingingStopped(sbn.key)
    }
}
