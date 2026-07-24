package org.jarsi.arkphone.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.data.model.SimCard
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

class SystemSimRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SimRepository {

    @SuppressLint("MissingPermission") // Guarded by the PermissionChecker checks below.
    override suspend fun activeSims(): List<SimCard> = withContext(ioDispatcher) {
        if (!permissionChecker.has(Manifest.permission.READ_PHONE_STATE)) return@withContext emptyList()
        val subscriptionManager =
            context.getSystemService(SubscriptionManager::class.java) ?: return@withContext emptyList()
        runCatching {
            (subscriptionManager.activeSubscriptionInfoList ?: emptyList()).map { info ->
                SimCard(
                    subscriptionId = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    displayName = info.displayName?.toString()?.takeIf { it.isNotBlank() },
                    carrierName = info.carrierName?.toString()?.takeIf { it.isNotBlank() },
                    number = resolveNumber(subscriptionManager, info),
                    countryIso = info.countryIso?.takeIf { it.isNotBlank() }?.uppercase(),
                    isEmbedded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.isEmbedded,
                )
            }
        }.getOrDefault(emptyList())
    }

    @SuppressLint("MissingPermission") // Guarded by the PermissionChecker checks below.
    private fun resolveNumber(
        subscriptionManager: SubscriptionManager,
        info: SubscriptionInfo,
    ): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!permissionChecker.has(Manifest.permission.READ_PHONE_NUMBERS)) return@runCatching null
            subscriptionManager.getPhoneNumber(info.subscriptionId).takeIf { it.isNotBlank() }
        } else {
            @Suppress("DEPRECATION")
            info.number?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}
