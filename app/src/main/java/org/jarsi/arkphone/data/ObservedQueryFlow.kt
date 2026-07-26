package org.jarsi.arkphone.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Emits [query] results, re-running the query on provider change
 * notifications and on every [refreshSignal] tick. The observer is only
 * registered once permission allows it — a refresh after a runtime grant
 * registers it late, so lists fill in without recreating the flow.
 */
internal fun <T> observedQueryFlow(
    hasPermission: () -> Boolean,
    registerObserver: (notifyChange: () -> Unit) -> Unit,
    unregisterObserver: () -> Unit,
    refreshSignal: Flow<Unit>,
    query: () -> T,
): Flow<T> = callbackFlow {
    var registered = false
    fun registerIfPermitted() {
        if (!registered && hasPermission()) {
            registerObserver { trySend(Unit) }
            registered = true
        }
    }
    registerIfPermitted()
    launch {
        refreshSignal.collect {
            registerIfPermitted()
            trySend(Unit)
        }
    }
    send(Unit)
    awaitClose {
        if (registered) unregisterObserver()
    }
}.conflate().map { query() }
