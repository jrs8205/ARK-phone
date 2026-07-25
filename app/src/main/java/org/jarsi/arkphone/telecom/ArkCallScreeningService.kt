package org.jarsi.arkphone.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.util.sameCaller
import javax.inject.Inject

/**
 * Rule-based blocking: as the default dialer's screening service this runs
 * before the call rings, so blocked calls stay silent, show no notification
 * and still land in the call log as blocked.
 */
@AndroidEntryPoint
class ArkCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "ArkPhone"
        private const val SETTINGS_TIMEOUT_MILLIS = 1_000L
        const val REPEAT_CALLER_WINDOW_MILLIS = 15 * 60_000L
    }

    @Inject lateinit var settingsCache: SettingsCache

    @Inject lateinit var contactsRepository: ContactsRepository

    @Inject lateinit var callLogRepository: CallLogRepository

    @Inject lateinit var clock: Clock

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onScreenCall(callDetails: Call.Details) {
        // Below Q the platform only screens incoming calls and has no
        // direction API; on Q+ make sure we never touch outgoing calls.
        val incoming = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        if (!incoming) {
            respondToCall(callDetails, CallResponse.Builder().build())
            return
        }
        scope.launch {
            val settings = withTimeoutOrNull(SETTINGS_TIMEOUT_MILLIS) { settingsCache.await() }
                ?: settingsCache.current
            val number = callDetails.handle?.schemeSpecificPart
            val block = if (number.isNullOrBlank()) {
                shouldBlockCall(number, isInContacts = false, isRepeatCaller = false, settings)
            } else {
                shouldBlockCall(
                    number = number,
                    isInContacts = contactsRepository.lookupContact(number) != null,
                    isRepeatCaller = isRepeatCaller(number),
                    settings = settings,
                )
            }
            val response = if (block) {
                Log.i(TAG, "Screening blocked an incoming call")
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(true)
                    .build()
            } else {
                CallResponse.Builder().build()
            }
            respondToCall(callDetails, response)
        }
    }

    private suspend fun isRepeatCaller(number: String): Boolean {
        val cutoff = clock.nowMillis() - REPEAT_CALLER_WINDOW_MILLIS
        return runCatching {
            callLogRepository.callLog().first()
                .any { it.timestampMillis >= cutoff && sameCaller(it.number, number) }
        }.getOrDefault(false)
    }
}
