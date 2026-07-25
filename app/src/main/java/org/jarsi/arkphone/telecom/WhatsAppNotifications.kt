package org.jarsi.arkphone.telecom

import android.app.Notification
import android.app.Person
import android.os.Build
import androidx.core.os.BundleCompat

internal data class WhatsAppCall(
    val callerName: String?,
    val callerNumber: String? = null,
    val isVideo: Boolean = false,
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
 * anything that is not a live call (other packages, non-call categories,
 * the missed-call summary). CallStyle notifications carry the call type;
 * plain formats are told apart by full-screen intent and tag — the ongoing
 * shape is the leftover case, verified against field diagnostics.
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
        tag?.contains("missed_calls") == true -> null
        else -> WhatsAppCallNotificationKind.ONGOING
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
