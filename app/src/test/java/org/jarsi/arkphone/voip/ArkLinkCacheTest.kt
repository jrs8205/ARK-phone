package org.jarsi.arkphone.voip

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.testing.FakeArkLinkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ArkLinkCacheTest {

    private fun link(number: String, code: String) = ArkLink(
        numberKey = arkLinkKey(number),
        number = number,
        code = code,
        nickname = "Jarsi",
        publicKey = "pk",
        linkedAtMillis = 1_000L,
    )

    @Test
    fun `an unlinked number has no link`() = runTest {
        val repository = FakeArkLinkRepository()
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        assertNull(cache.linkFor("+358 44 5552841"))
    }

    @Test
    fun `a linked number matches in any spelling`() = runTest {
        val repository = FakeArkLinkRepository()
        repository.state.value = listOf(link("+358 44 5552841", "ARK-7K3M-Q2FP"))
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        assertEquals("ARK-7K3M-Q2FP", cache.linkFor("044 555 2841")?.code)
    }

    @Test
    fun `refresh reads the table now rather than waiting for the collector`() = runTest {
        // A restore has just replaced the table; the next call is admitted
        // against the cache, which must not still hold the old links.
        val repository = FakeArkLinkRepository()
        repository.state.value = listOf(link("+358 44 5552841", "ARK-7K3M-Q2FP"))
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        // The collector has not run since: nothing advances the scheduler.
        repository.state.value = listOf(link("+358 40 1234567", "ARK-AAAA-AAAA"))

        cache.refresh()

        assertNull(cache.linkFor("044 555 2841"))
        assertEquals("ARK-AAAA-AAAA", cache.linkFor("040 123 4567")?.code)
    }

    @Test
    fun `a number with no digits never matches`() = runTest {
        val repository = FakeArkLinkRepository()
        repository.state.value = listOf(link("+358 44 5552841", "ARK-7K3M-Q2FP"))
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        assertNull(cache.linkFor(""))
    }

    @Test
    fun `an old query cannot overwrite a refresh and the new subscription keeps updating`() = runTest {
        val old = link("+358445552841", "ARK-7K3M-Q2FP")
        val restored = link("+358401234567", "ARK-AAAA-AAAA")
        val rows = MutableStateFlow(listOf(old))
        val delayed = CompletableDeferred<Unit>()
        var subscriptions = 0
        val repository = object : ArkLinkRepository by FakeArkLinkRepository() {
            override val links = flow {
                if (++subscriptions == 1) {
                    emit(rows.value)
                    val stale = rows.value
                    delayed.await()
                    emit(stale)
                    awaitCancellation()
                } else {
                    emitAll(rows)
                }
            }
        }
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        rows.value = listOf(restored)

        cache.refresh()
        delayed.complete(Unit)
        runCurrent()

        assertNull(cache.linkFor(old.number))
        assertEquals(restored, cache.linkFor(restored.number))
        rows.value = emptyList()
        runCurrent()
        assertNull(cache.linkFor(restored.number))
    }

    @Test
    fun `refresh before the initial query completes still allows await to finish`() = runTest {
        var subscriptions = 0
        val querying = CompletableDeferred<Unit>()
        val restored = link("+358401234567", "ARK-AAAA-AAAA")
        val repository = object : ArkLinkRepository by FakeArkLinkRepository() {
            override val links = flow {
                if (++subscriptions == 1) {
                    querying.complete(Unit)
                    awaitCancellation()
                }
                emit(listOf(restored))
                awaitCancellation()
            }
        }
        val cache = ArkLinkCache(repository, backgroundScope)
        querying.await()

        cache.refresh()

        assertEquals(mapOf(restored.numberKey to restored), cache.await())
    }

    @Test
    fun `concurrent refreshes wait for each subscription in order`() = runTest {
        var subscriptions = 0
        val queried = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Unit>()
        val repository = object : ArkLinkRepository by FakeArkLinkRepository() {
            override val links = flow<List<ArkLink>> {
                if (++subscriptions == 2) {
                    queried.complete(Unit)
                    result.await()
                }
                emit(emptyList())
                awaitCancellation()
            }
        }
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        val first = async { cache.refresh() }
        queried.await()
        val second = async { cache.refresh() }
        runCurrent()
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertEquals(2, subscriptions)

        result.complete(Unit)
        first.await()
        second.await()

        assertEquals(3, subscriptions)
    }

    @Test
    fun `a failed refresh reports its failure and can be retried`() = runTest {
        var refuse = false
        val old = link("+358445552841", "ARK-7K3M-Q2FP")
        val rows = MutableStateFlow(listOf(old))
        val repository = object : ArkLinkRepository by FakeArkLinkRepository() {
            override val links = flow {
                if (refuse) throw IOException("database unavailable")
                emitAll(rows)
            }
        }
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        refuse = true
        try {
            cache.refresh()
            fail("expected IOException")
        } catch (_: IOException) {
            // The caller must know that the cache has not been refreshed.
        }
        assertNull(cache.linkFor(old.number))
        refuse = false
        val restored = link("+358401234567", "ARK-AAAA-AAAA")
        rows.value = listOf(restored)

        cache.refresh()

        assertEquals(restored, cache.linkFor(restored.number))
    }

    @Test
    fun `cancelling a refresh during shutdown still leaves a live subscription`() = runTest {
        val stopping = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val rows = MutableStateFlow(emptyList<ArkLink>())
        var subscriptions = 0
        val repository = object : ArkLinkRepository by FakeArkLinkRepository() {
            override val links = flow {
                if (++subscriptions == 1) {
                    try {
                        emit(emptyList<ArkLink>())
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            stopping.complete(Unit)
                            stopped.await()
                        }
                    }
                } else {
                    emitAll(rows)
                }
            }
        }
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        val refresh = launch { cache.refresh() }
        stopping.await()
        refresh.cancel()
        stopped.complete(Unit)
        refresh.join()
        val restored = link("+358401234567", "ARK-AAAA-AAAA")
        rows.value = listOf(restored)
        runCurrent()

        assertEquals(restored, cache.linkFor(restored.number))
    }
}
