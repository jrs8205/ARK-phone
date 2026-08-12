package org.jarsi.arkphone.messaging

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** The subscription id of the device's default messaging SIM, or -1. */
internal fun defaultMessagingSubscriptionId(): Int =
    runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1)

/** The [SmsManager] of the device's default messaging SIM. */
internal fun Context.defaultSmsManager(): SmsManager =
    smsManagerFor(defaultMessagingSubscriptionId())

/** The [SmsManager] of the given subscription; -1 means the default
 *  messaging SIM. */
internal fun Context.smsManagerFor(subscriptionId: Int): SmsManager {
    val resolved = if (subscriptionId >= 0) subscriptionId else defaultMessagingSubscriptionId()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = getSystemService(SmsManager::class.java)
        return if (resolved >= 0) {
            manager.createForSubscriptionId(resolved)
        } else {
            manager
        }
    }
    @Suppress("DEPRECATION")
    return if (resolved >= 0) {
        SmsManager.getSmsManagerForSubscriptionId(resolved)
    } else {
        SmsManager.getDefault()
    }
}
