package org.jarsi.arkphone.voip.fcm

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
        // Blocking is the point: Firebase holds a wake lock and elevated
        // process priority only until this method returns, and a dozed phone
        // needs both through the connect, the flush drain and the ring.
        // Firebase's own budget for this callback is well past the cap here.
        runBlocking {
            withTimeoutOrNull(WAKE_HOLD_MS) { engine.awaitWake() }
        }
        Log.i(TAG, "Wake handling finished")
    }

    override fun onNewToken(token: String) {
        appScope.launch { tokenSync.sync(token) }
    }

    private companion object {
        const val TAG = "ArkPhone"
        const val TYPE_INCOMING_CALL = "incoming-call"

        /** Below Firebase's ~10 s intent-handling budget, above connect+drain. */
        const val WAKE_HOLD_MS = 9_500L
    }
}
