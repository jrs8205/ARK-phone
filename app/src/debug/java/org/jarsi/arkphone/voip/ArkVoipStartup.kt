package org.jarsi.arkphone.voip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Brings the VoIP engine up with the process: the FCM token is refreshed so
 * the worker can wake this device, the inbox socket opens, and everything the
 * flush reconciles into a call is handed to the call coordinator.
 */
class ArkVoipStartup(
    private val engine: VoipEngine,
    private val onIncoming: (IncomingArkCall) -> Unit,
    private val fcmRefresh: () -> Unit,
    private val scope: CoroutineScope,
) : VoipStartup {

    private var started: Job? = null

    override fun onAppStart() {
        if (started != null) return
        fcmRefresh()
        scope.launch { engine.incomingCalls.collect(onIncoming) }
        started = scope.launch { engine.connect() }
    }
}
