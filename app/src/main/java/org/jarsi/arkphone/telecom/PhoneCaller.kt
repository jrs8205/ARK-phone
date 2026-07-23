package org.jarsi.arkphone.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

class PhoneCaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
) {
    /** Returns false when the CALL_PHONE permission is missing; the caller should surface onboarding. */
    @SuppressLint("MissingPermission") // Guarded by the PermissionChecker check below.
    fun placeCall(number: String): Boolean {
        if (number.isBlank()) return false
        if (!permissionChecker.has(Manifest.permission.CALL_PHONE)) return false
        val telecomManager = context.getSystemService(TelecomManager::class.java) ?: return false
        val uri = Uri.fromParts("tel", number, null)
        telecomManager.placeCall(uri, Bundle())
        return true
    }
}
