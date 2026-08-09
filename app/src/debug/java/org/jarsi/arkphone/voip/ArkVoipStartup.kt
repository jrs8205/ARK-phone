package org.jarsi.arkphone.voip

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.ArkIdentity

/**
 * Brings the VoIP engine up with the process: the FCM token is refreshed so
 * the worker can wake this device, the inbox socket opens as soon as the
 * device HAS an identity — at process start when already registered, or the
 * moment registration completes — and everything the flush reconciles into a
 * call is handed to the call coordinator.
 */
class ArkVoipStartup(
    private val engine: VoipEngine,
    private val onIncoming: (IncomingArkCall) -> Unit,
    private val fcmRefresh: () -> Unit,
    private val identities: Flow<ArkIdentity?>,
    private val scope: CoroutineScope,
) : VoipStartup {

    private var started = false

    override fun onAppStart() {
        if (started) return
        started = true
        fcmRefresh()
        scope.launch { engine.incomingCalls.collect(onIncoming) }
        scope.launch {
            // Re-fires on registration: a device that registers mid-process
            // must open its inbox without waiting for a restart or a call.
            identities.map { it != null }.distinctUntilChanged().collect { registered ->
                if (registered) {
                    // A token that arrived pre-registration is only pending;
                    // this refresh is what actually posts it to the worker.
                    fcmRefresh()
                    val connected = engine.connect()
                    Log.i(TAG, "ARK inbox connect=$connected")
                }
            }
        }
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
