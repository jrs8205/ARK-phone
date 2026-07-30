package org.jarsi.arkphone.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

data class MessagingSim(val subscriptionId: Int, val label: String)

interface MessagingSims {
    /** Active SIMs able to send messages; empty without permission. */
    fun sims(): List<MessagingSim>

    /** Android's default messaging subscription, or the only SIM's id, or -1. */
    fun defaultSubscriptionId(): Int
}

/** The only-SIM fallback when Android has no default messaging subscription. */
internal fun pickDefaultSubscription(defaultId: Int, active: List<MessagingSim>): Int = when {
    defaultId >= 0 -> defaultId
    active.size == 1 -> active.single().subscriptionId
    else -> -1
}

class AndroidMessagingSims @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
) : MessagingSims {

    @SuppressLint("MissingPermission") // Guarded by the PermissionChecker check below.
    override fun sims(): List<MessagingSim> {
        if (!permissionChecker.has(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val subscriptionManager =
            context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching {
            (subscriptionManager.activeSubscriptionInfoList ?: emptyList()).map { info ->
                MessagingSim(
                    subscriptionId = info.subscriptionId,
                    label = info.displayName?.toString()?.takeIf { it.isNotBlank() }
                        ?: "SIM ${info.simSlotIndex + 1}",
                )
            }
        }.getOrDefault(emptyList())
    }

    override fun defaultSubscriptionId(): Int =
        pickDefaultSubscription(SubscriptionManager.getDefaultSmsSubscriptionId(), sims())
}
