package org.jarsi.arkphone.voip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    repository: ArkLinkRepository,
    @ApplicationScope scope: CoroutineScope,
) {

    private val firstLoad = CompletableDeferred<Unit>()

    private val state = MutableStateFlow<Map<String, ArkLink>>(emptyMap())

    init {
        scope.launch {
            repository.links.collect { links ->
                state.value = links.associateBy { it.numberKey }
                if (!firstLoad.isCompleted) firstLoad.complete(Unit)
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
}
