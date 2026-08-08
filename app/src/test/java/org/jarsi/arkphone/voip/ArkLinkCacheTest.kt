package org.jarsi.arkphone.voip

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.testing.FakeArkLinkRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
    fun `a number with no digits never matches`() = runTest {
        val repository = FakeArkLinkRepository()
        repository.state.value = listOf(link("+358 44 5552841", "ARK-7K3M-Q2FP"))
        val cache = ArkLinkCache(repository, backgroundScope)
        cache.await()
        assertNull(cache.linkFor(""))
    }
}
