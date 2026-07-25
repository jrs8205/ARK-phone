package org.jarsi.arkphone.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
            val decision = ruleEvaluator.evaluate(callDetails.handle?.schemeSpecificPart)
            Log.i(TAG, "Screening decision: ${decision.details}")
            val response = if (decision.block) {
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
}
