package org.jarsi.arkphone.messaging

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject

/** Sends the in-call "reject with message" text on behalf of the system.
 *  Required from the default SMS app; never shows UI. */
@AndroidEntryPoint
class HeadlessSmsSendService : Service() {

    @Inject lateinit var smsSender: SmsSender

    @Inject lateinit var messagingSims: MessagingSims

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val recipient = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE &&
            !recipient.isNullOrBlank() &&
            !text.isNullOrBlank()
        ) {
            scope.launch {
                smsSender.send(recipient, text, messagingSims.defaultSubscriptionId())
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
}
