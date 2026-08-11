package org.jarsi.arkphone.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject

/** The default SMS app's MMS entry point: a WAP push carrying the
 *  m-notification-ind bytes in the "data" extra. */
@AndroidEntryPoint
class WapPushReceiver : BroadcastReceiver() {

    @Inject lateinit var downloader: MmsDownloader

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra("data") ?: return
        val subscriptionId = intent.getIntExtra("subscription", -1)
        val pendingResult = goAsync()
        scope.launch {
            try {
                downloader.onPush(pdu, subscriptionId)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
