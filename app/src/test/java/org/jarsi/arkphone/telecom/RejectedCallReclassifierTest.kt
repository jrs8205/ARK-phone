package org.jarsi.arkphone.telecom

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RejectedCallReclassifierTest {

    private val repository = FakeCallLogRepository()
    private val reclassifier = RejectedCallReclassifier(repository)

    @Test
    fun returnsTrueOnFirstSuccess() = runTest {
        assertTrue(reclassifier.markBlocked("0401234567", 1_000L))
        assertEquals(listOf("0401234567" to 1_000L), repository.reclassifyRequests)
    }

    @Test
    fun retriesUntilTheLogRowAppears() = runTest {
        repository.reclassifyResults.addAll(listOf(false, false, false))
        assertTrue(reclassifier.markBlocked("0401234567", 1_000L))
        assertEquals(4, repository.reclassifyRequests.size)
    }

    @Test
    fun givesUpAfterMaxAttempts() = runTest {
        repeat(RejectedCallReclassifier.MAX_ATTEMPTS + 5) {
            repository.reclassifyResults.add(false)
        }
        assertFalse(reclassifier.markBlocked("0401234567", 1_000L))
        assertEquals(RejectedCallReclassifier.MAX_ATTEMPTS, repository.reclassifyRequests.size)
    }
}
