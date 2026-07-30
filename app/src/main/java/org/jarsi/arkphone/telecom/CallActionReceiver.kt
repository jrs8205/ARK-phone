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
    @Inject lateinit var blockedCallNotifier: BlockedCallNotifier

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CallNotifications.ACTION_DECLINE -> {
                intent.getStringExtra(CallNotifications.EXTRA_CALL_ID)?.let(callController::reject)
            }
            MissedCallNotifier.ACTION_CALL_BACK -> {
                // goAsync keeps the process alive until the call-log write
                // lands; otherwise the calls stay new=1 and boot re-posts them.
                val pending = goAsync()
                missedCallNotifier.onCallLogSeen { pending.finish() }
                intent.getStringExtra(MissedCallNotifier.EXTRA_NUMBER)?.let(phoneCaller::placeCall)
            }
            MissedCallNotifier.ACTION_MISSED_DISMISSED -> {
                val pending = goAsync()
                missedCallNotifier.onCallLogSeen { pending.finish() }
            }
            BlockedCallNotifier.ACTION_BLOCKED_DISMISSED -> blockedCallNotifier.onSeen()
        }
    }
}
