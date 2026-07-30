package org.jarsi.arkphone.messaging

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager

/** The subscription id of the device's default messaging SIM, or -1. */
internal fun defaultMessagingSubscriptionId(): Int =
    runCatching { SubscriptionManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1)

/** The [SmsManager] of the device's default messaging SIM. */
internal fun Context.defaultSmsManager(): SmsManager {
    val subscriptionId = defaultMessagingSubscriptionId()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = getSystemService(SmsManager::class.java)
        return if (subscriptionId >= 0) {
            manager.createForSubscriptionId(subscriptionId)
        } else {
            manager
        }
    }
    @Suppress("DEPRECATION")
    return if (subscriptionId >= 0) {
        SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
    } else {
        SmsManager.getDefault()
    }
}
