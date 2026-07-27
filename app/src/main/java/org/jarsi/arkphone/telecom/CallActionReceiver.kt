package org.jarsi.arkphone.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var callController: CallController
    @Inject lateinit var phoneCaller: PhoneCaller
    @Inject lateinit var missedCallNotifier: MissedCallNotifier

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CallNotifications.ACTION_DECLINE -> {
                intent.getStringExtra(CallNotifications.EXTRA_CALL_ID)?.let(callController::reject)
            }
            MissedCallNotifier.ACTION_CALL_BACK -> {
                missedCallNotifier.onCallLogSeen()
                intent.getStringExtra(MissedCallNotifier.EXTRA_NUMBER)?.let(phoneCaller::placeCall)
            }
            MissedCallNotifier.ACTION_MISSED_DISMISSED -> missedCallNotifier.onCallLogSeen()
        }
    }
}
