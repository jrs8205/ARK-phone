package org.jarsi.arkphone.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.data.model.SimAccount
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

/**
 * The SIMs that can place calls, as Telecom phone accounts. This is a
 * different view from [SimRepository], which describes the SIM hardware for
 * the info page: only accounts appear in the call log, in call details and
 * in outgoing call requests, so they are the identity used for SIM features.
 */
interface SimAccountRepository {
    suspend fun accounts(): List<SimAccount>

    /** The Telecom handle for [accountId], or null when that SIM is gone.
     *  Synchronous: placing a call must not wait on a provider round trip. */
    fun handleFor(accountId: String): PhoneAccountHandle?
}

class TelecomSimAccountRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SimAccountRepository {

    @SuppressLint("MissingPermission") // Guarded by the PermissionChecker check below.
    override suspend fun accounts(): List<SimAccount> = withContext(ioDispatcher) {
        if (!permissionChecker.has(Manifest.permission.READ_PHONE_STATE)) return@withContext emptyList()
        val telecomManager = context.getSystemService(TelecomManager::class.java)
            ?: return@withContext emptyList()
        runCatching {
            telecomManager.callCapablePhoneAccounts.mapNotNull { handle ->
                simAccountFor(telecomManager, handle)
            }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission") // Guarded by the PermissionChecker check below.
    override fun handleFor(accountId: String): PhoneAccountHandle? {
        if (!permissionChecker.has(Manifest.permission.READ_PHONE_STATE)) return null
        val telecomManager = context.getSystemService(TelecomManager::class.java) ?: return null
        return runCatching {
            telecomManager.callCapablePhoneAccounts.firstOrNull { it.id == accountId }
        }.getOrNull()
    }

    private fun simAccountFor(
        telecomManager: TelecomManager,
        handle: PhoneAccountHandle,
    ): SimAccount? {
        val account = telecomManager.getPhoneAccount(handle) ?: return null
        val label = account.label?.toString()?.takeIf { it.isNotBlank() } ?: return null
        // The account address is a tel: URI when the operator provides the number.
        val number = account.address
            ?.takeIf { it.scheme == "tel" }
            ?.schemeSpecificPart
            ?.takeIf { it.isNotBlank() }
        return SimAccount(id = handle.id, label = label, number = number)
    }
}
