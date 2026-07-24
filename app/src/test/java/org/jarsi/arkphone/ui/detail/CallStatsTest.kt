package org.jarsi.arkphone.ui.detail

import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallStatsTest {

    private fun entry(id: Long, type: CallType, at: Long, duration: Long = 0) = CallLogEntry(
        id = id,
        number = "0401234567",
        displayName = null,
        type = type,
        timestampMillis = at,
        durationSeconds = duration,
    )

    @Test
    fun computesCountsDurationAndLatest() {
        val stats = computeCallStats(
            listOf(
                entry(1, CallType.OUTGOING, at = 3_000, duration = 60),
                entry(2, CallType.INCOMING, at = 9_000, duration = 120),
                entry(3, CallType.MISSED, at = 5_000),
                entry(4, CallType.INCOMING, at = 1_000, duration = 30),
            ),
        )
        assertEquals(4, stats.total)
        assertEquals(2, stats.incoming)
        assertEquals(1, stats.outgoing)
        assertEquals(1, stats.missed)
        assertEquals(210L, stats.totalDurationSeconds)
        assertEquals(9_000L, stats.latestMillis)
    }

    @Test
    fun emptyHistoryHasNoLatestCall() {
        val stats = computeCallStats(emptyList())
        assertEquals(0, stats.total)
        assertNull(stats.latestMillis)
    }
}
