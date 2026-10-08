package org.jarsi.arkphone.voip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Warm in-memory copy of the link table for the outgoing-call path, following
 * the SettingsCache pattern: [linkFor] is synchronous so an unlinked number
 * costs nothing, and [await] exists for tests and cold starts. Holding an
 * empty map before the first emission is safe — the worst case is a carrier
 * call, which is exactly the required degradation.
 */
@Singleton
class ArkLinkCache @Inject constructor(
    private val repository: ArkLinkRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {

    private val firstLoad = CompletableDeferred<Unit>()

    private val state = MutableStateFlow<Map<String, ArkLink>>(emptyMap())

    private val refreshLock = Mutex()
    private var collector = collectLinks(CompletableDeferred())

    private fun collectLinks(loaded: CompletableDeferred<Unit>): Job = scope.launch {
        try {
            repository.links.collect { links ->
                state.value = links.associateBy { it.numberKey }
                if (!firstLoad.isCompleted) firstLoad.complete(Unit)
                loaded.complete(Unit)
            }
        } catch (e: Exception) {
            if (e !is CancellationException) state.value = emptyMap()
            loaded.completeExceptionally(e)
            if (e is CancellationException) throw e
        }
    }.also { job ->
        // A cancelled application scope may never enter the launch body.
        job.invokeOnCompletion { cause ->
            if (!loaded.isCompleted) {
                loaded.completeExceptionally(cause ?: NoSuchElementException("No ARK links emission"))
            }
        }
    }

    val current: Map<String, ArkLink> get() = state.value

    fun linkFor(number: String): ArkLink? {
        val key = arkLinkKey(number)
        if (key.isEmpty()) return null
        return state.value[key]
    }

    suspend fun await(): Map<String, ArkLink> {
        firstLoad.await()
        return state.value
    }

    /**
     * Replaces the subscription and waits for its first result. No emission
     * from the old query can overwrite the restored links after this returns.
     */
    suspend fun refresh() = refreshLock.withLock {
        val loaded = CompletableDeferred<Unit>()
        // Once the old subscription is stopped, its replacement must be
        // started even if the caller leaves while the old query shuts down.
        withContext(NonCancellable) {
            collector.cancelAndJoin()
            collector = collectLinks(loaded)
        }
        loaded.await()
    }
}
