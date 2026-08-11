package org.jarsi.arkphone.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.data.SimAccountRepository
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

/** Sends the "decline with message" SMS; false when SEND_SMS is missing or
 *  sending failed. [simAccountId] is the account the call rang on, so the
 *  reply leaves over the same SIM the caller dialed. */
fun interface RejectMessageSender {
    fun send(number: String, message: String, simAccountId: String?): Boolean
}

class SmsRejectMessageSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
    private val simAccountRepository: SimAccountRepository,
) : RejectMessageSender {

    @SuppressLint("MissingPermission") // Guarded by the PermissionChecker check below.
    override fun send(number: String, message: String, simAccountId: String?): Boolean {
        if (number.isBlank() || message.isBlank()) return false
        if (!permissionChecker.has(Manifest.permission.SEND_SMS)) return false
        return runCatching {
            val smsManager = smsManagerFor(subscriptionIdFor(simAccountId)) ?: return false
            smsManager.sendTextMessage(number, null, message, null, null)
            true
        }.getOrDefault(false)
    }

    /** The subscription of the SIM the call rang on, or -1 to use the default. */
    // handleFor already refuses without READ_PHONE_STATE, and runCatching
    // covers a SecurityException with the default-SIM fallback.
    @SuppressLint("MissingPermission")
    private fun subscriptionIdFor(simAccountId: String?): Int {
        simAccountId ?: return -1
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1
        val handle = simAccountRepository.handleFor(simAccountId) ?: return -1
        return runCatching {
            context.getSystemService(TelephonyManager::class.java)?.getSubscriptionId(handle)
        }.getOrNull() ?: -1
    }

    private fun smsManagerFor(subscriptionId: Int): SmsManager? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (subscriptionId >= 0) manager?.createForSubscriptionId(subscriptionId) else manager
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId >= 0) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }
}
