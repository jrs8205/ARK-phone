package org.jarsi.arkphone.voip.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.voip.VoipEngine
import javax.inject.Inject

/**
 * The wake-up path. The push is data-only and carries
 * `{type:"incoming-call", from:<code>}`, but `from` names only the first
 * caller inside the worker's 10 s per-target cooldown — a second caller inside
 * that window produces no push at all. The payload is therefore a wake signal
 * and nothing more: who is actually calling comes from the buffered messages
 * the inbox flushes on connect.
 */
@AndroidEntryPoint
class ArkMessagingService : FirebaseMessagingService() {

    @Inject lateinit var engine: VoipEngine

    @Inject lateinit var tokenSync: FcmTokenSync

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != TYPE_INCOMING_CALL) return
        Log.i(TAG, "Wake push received; connecting the inbox")
        engine.onWake()
    }

    override fun onNewToken(token: String) {
        appScope.launch { tokenSync.sync(token) }
    }

    private companion object {
        const val TAG = "ArkPhone"
        const val TYPE_INCOMING_CALL = "incoming-call"
    }
}
