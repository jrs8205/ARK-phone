package org.jarsi.arkphone.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var callController: CallController

    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(CallNotifications.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            CallNotifications.ACTION_ANSWER -> callController.answer(callId)
            CallNotifications.ACTION_DECLINE -> callController.reject(callId)
        }
    }
}
