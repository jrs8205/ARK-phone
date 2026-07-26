package org.jarsi.arkphone.data

import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RejectedRowReclassifyTest {

    private fun row(
        id: Long,
        number: String = "0401234567",
        type: CallType = CallType.REJECTED,
        at: Long = 10_000,
    ) = CallLogEntry(
        id = id, number = number, displayName = null,
        type = type, timestampMillis = at, durationSeconds = 0,
        source = CallSource.PHONE,
    )

    @Test
    fun picksTheNewestMatchingRejectedRow() {
        val rows = listOf(
            row(1, at = 10_000),
            row(2, at = 12_000),
            row(3, number = "0501112223", at = 13_000),
        )
        assertEquals(2L, rejectedRowToReclassify(rows, "0401234567", 9_000))
    }

    @Test
    fun matchesNationalAgainstInternationalForm() {
        val rows = listOf(row(1, number = "+358401234567"))
        assertEquals(1L, rejectedRowToReclassify(rows, "0401234567", 0))
    }

    @Test
    fun ignoresRowsOlderThanTheDecision() {
        assertNull(rejectedRowToReclassify(listOf(row(1, at = 5_000)), "0401234567", 9_000))
    }

    @Test
    fun ignoresNonRejectedTypes() {
        val rows = listOf(
            row(1, type = CallType.MISSED),
            row(2, type = CallType.INCOMING),
            row(3, type = CallType.BLOCKED),
            row(4, type = CallType.OUTGOING),
        )
        assertNull(rejectedRowToReclassify(rows, "0401234567", 0))
    }

    @Test
    fun ignoresRowsFromOtherNumbers() {
        assertNull(rejectedRowToReclassify(listOf(row(1, number = "0501112223")), "0401234567", 0))
    }

    @Test
    fun aHiddenCallerMatchesOnlyNumberlessRows() {
        val rows = listOf(row(1, number = "0401234567"), row(2, number = ""))
        assertEquals(2L, rejectedRowToReclassify(rows, "", 0))
    }

    @Test
    fun aRealNumberDoesNotMatchNumberlessRows() {
        assertNull(rejectedRowToReclassify(listOf(row(1, number = "")), "0401234567", 0))
    }
}
