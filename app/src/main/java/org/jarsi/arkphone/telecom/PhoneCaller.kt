package org.jarsi.arkphone.telecom

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.data.SimAccountRepository
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

/** Hands the finished call request to Telecom. */
fun interface TelecomCallPlacer {
    fun place(uri: Uri, accountHandle: PhoneAccountHandle?)
}

class AndroidTelecomCallPlacer @Inject constructor(
    @ApplicationContext private val context: Context,
) : TelecomCallPlacer {
    @SuppressLint("MissingPermission") // PhoneCaller checks CALL_PHONE before calling this.
    override fun place(uri: Uri, accountHandle: PhoneAccountHandle?) {
        val telecomManager = context.getSystemService(TelecomManager::class.java) ?: return
        val extras = Bundle().apply {
            accountHandle?.let { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
        }
        telecomManager.placeCall(uri, extras)
    }
}

class PhoneCaller @Inject constructor(
    private val permissionChecker: PermissionChecker,
    private val simAccountRepository: SimAccountRepository,
    private val settingsCache: SettingsCache,
    private val callPlacer: TelecomCallPlacer,
) {
    /** Returns false when the CALL_PHONE permission is missing; the caller should surface onboarding. */
    fun placeCall(number: String): Boolean {
        if (number.isBlank()) return false
        return place(Uri.fromParts("tel", number, null))
    }

    /** Calls the operator voicemail; false when CALL_PHONE is missing. */
    fun placeVoicemailCall(): Boolean = place(Uri.fromParts("voicemail", "", null))

    private fun place(uri: Uri): Boolean {
        if (!permissionChecker.has(Manifest.permission.CALL_PHONE)) return false
        callPlacer.place(uri, chosenAccountHandle())
        return true
    }

    /** The user's SIM choice, or null to let the system pick — which is also
     *  what happens once the chosen SIM is no longer in the device. */
    private fun chosenAccountHandle(): PhoneAccountHandle? {
        // The settings cache is read synchronously: an outgoing call is always
        // started from the UI, long after the first DataStore emission.
        val chosenId = settingsCache.current.callSimAccountId ?: return null
        return simAccountRepository.handleFor(chosenId)
    }
}
