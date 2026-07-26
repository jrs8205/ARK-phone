package org.jarsi.arkphone.telecom

import android.app.Notification
import android.app.Person
import android.os.Build
import androidx.core.os.BundleCompat

internal data class WhatsAppCall(
    val callerName: String?,
    val callerNumber: String? = null,
    val isVideo: Boolean = false,
    /** com.whatsapp or com.whatsapp.w4b — which app posted the notification. */
    val sourcePackage: String = "com.whatsapp",
)

internal enum class WhatsAppCallNotificationKind { RINGING, ONGOING }

private const val EXTRA_CALL_TYPE = "android.callType"
private const val EXTRA_CALL_PERSON = "android.callPerson"
private const val CALL_TYPE_INCOMING = 1
private const val CALL_TYPE_ONGOING = 2

/** Multi-account WhatsApp titles the ringing notification with the receiving
 *  account as "[ +358 45 ... ]" — the caller is only on the text line. */
private val ACCOUNT_TITLE = Regex("""^\[.*]$""")
private val PHONE_NUMBER = Regex("""\+?\d[\d\s()\-]{5,}\d""")
private val CALLER_SEPARATORS = listOf(" henkilöltä ", " from ")

/**
 * Sorts a WhatsApp call notification into ringing or ongoing, or null for
 * anything else. Both kinds need a positive signal (call type, full-screen
 * intent, ringing tag, chronometer): WhatsApp reuses one notification for the
 * whole call and refreshes it with a signal-less transitional shape that can
 * mean "connecting" OR just a lock-screen redraw while still ringing — field
 * data showed it must classify as neither.
 */
internal fun classifyWhatsAppCallNotification(
    packageName: String,
    notification: Notification,
    tag: String?,
): WhatsAppCallNotificationKind? {
    if (packageName != "com.whatsapp" && packageName != "com.whatsapp.w4b") return null
    if (notification.category != Notification.CATEGORY_CALL) return null
    val callType = notification.extras.getInt(EXTRA_CALL_TYPE, -1)
    return when {
        callType == CALL_TYPE_INCOMING -> WhatsAppCallNotificationKind.RINGING
        callType == CALL_TYPE_ONGOING -> WhatsAppCallNotificationKind.ONGOING
        callType != -1 -> null
        notification.fullScreenIntent != null -> WhatsAppCallNotificationKind.RINGING
        tag?.contains("ringing_call") == true -> WhatsAppCallNotificationKind.RINGING
        notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) ->
            WhatsAppCallNotificationKind.ONGOING
        else -> null
    }
}

/** The caller of a WhatsApp call notification, best effort. */
internal fun whatsAppCaller(notification: Notification): WhatsAppCall {
    val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    val isVideo = text?.contains("video", ignoreCase = true) == true
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
    val caller = when {
        name != null -> WhatsAppCall(callerName = name)
        text != null -> callerFromText(text)
        else -> WhatsAppCall(callerName = null)
    }
    return caller.copy(isVideo = isVideo)
}

/**
 * Recognizes a ringing WhatsApp call notification, or null for everything
 * else, including ongoing calls and the missed-call summary.
 */
internal fun whatsAppIncomingCall(
    packageName: String,
    notification: Notification,
    tag: String? = null,
): WhatsAppCall? {
    val kind = classifyWhatsAppCallNotification(packageName, notification, tag)
    if (kind != WhatsAppCallNotificationKind.RINGING) return null
    return whatsAppCaller(notification)
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
