package org.jarsi.arkphone.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.data.model.BlockedCallAction
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject

/**
 * Rule-based blocking for numbers NOT in the contacts — Android never feeds
 * saved contacts to a third-party screening service, so ArkInCallService
 * runs the same rules for those. Blocked calls here stay silent, show no
 * notification and still land in the call log as blocked.
 */
@AndroidEntryPoint
class ArkCallScreeningService : CallScreeningService() {

    companion object {
        private const val TAG = "ArkPhone"
    }

    @Inject lateinit var ruleEvaluator: CallRuleEvaluator

    @Inject lateinit var settingsCache: SettingsCache

    @Inject lateinit var blockedCallNotifier: BlockedCallNotifier

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
            val number = callDetails.handle?.schemeSpecificPart
            val decision = ruleEvaluator.evaluate(
                number = number,
                simAccountId = callDetails.accountHandle?.id,
            )
            // The cache is warm after evaluate() awaited it.
            val action = settingsCache.current.blockedCallAction
            Log.i(TAG, "Screening decision: action=$action ${decision.details}")
            val response = if (decision.block && action == BlockedCallAction.REJECT) {
                blockedCallNotifier.onCallBlocked(number)
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(true)
                    .setSkipNotification(true)
                    .build()
            } else {
                // Voicemail mode lets a blocked call through on purpose: the
                // in-call service silences it locally and it rings out to the
                // carrier voicemail. It also posts the blocked notification,
                // so this path must not double-count.
                CallResponse.Builder().build()
            }
            respondToCall(callDetails, response)
        }
    }
}
