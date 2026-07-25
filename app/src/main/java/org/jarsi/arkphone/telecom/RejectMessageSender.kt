package org.jarsi.arkphone.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

/** Sends the "decline with message" SMS; false when SEND_SMS is missing or sending failed. */
fun interface RejectMessageSender {
    fun send(number: String, message: String): Boolean
}

class SmsRejectMessageSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
) : RejectMessageSender {

    @SuppressLint("MissingPermission") // Guarded by the PermissionChecker check below.
    override fun send(number: String, message: String): Boolean {
        if (number.isBlank() || message.isBlank()) return false
        if (!permissionChecker.has(Manifest.permission.SEND_SMS)) return false
        return runCatching {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return false
            smsManager.sendTextMessage(number, null, message, null, null)
            true
        }.getOrDefault(false)
    }
}
