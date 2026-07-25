package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.jarsi.arkphone.testing.FakeWhatsAppCallLogRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class CombinedCallLogRepositoryTest {

    private val system = FakeCallLogRepository()
    private val whatsApp = FakeWhatsAppCallLogRepository()
    private val repository = CombinedCallLogRepository(system, whatsApp)

    private fun entry(
        id: Long,
        timestampMillis: Long,
        source: CallSource = CallSource.PHONE,
    ) = CallLogEntry(
        id = id,
        number = "0401234567",
        displayName = null,
        type = CallType.INCOMING,
        timestampMillis = timestampMillis,
        durationSeconds = 10,
        source = source,
    )

    @Test
    fun mergesBothLogsNewestFirst() = runTest {
        system.entries.value = listOf(entry(1, 3_000), entry(2, 1_000))
        whatsApp.entries.value = listOf(entry(10, 2_000, CallSource.WHATSAPP))
        val merged = repository.callLog().first()
        assertEquals(listOf(3_000L, 2_000L, 1_000L), merged.map { it.timestampMillis })
        assertEquals(
            listOf(CallSource.PHONE, CallSource.WHATSAPP, CallSource.PHONE),
            merged.map { it.source },
        )
    }

    @Test
    fun whatsAppIdsAreNegatedToStayUniqueAgainstSystemIds() = runTest {
        system.entries.value = listOf(entry(5, 3_000))
        whatsApp.entries.value = listOf(entry(5, 2_000, CallSource.WHATSAPP))
        assertEquals(listOf(5L, -5L), repository.callLog().first().map { it.id })
    }

    @Test
    fun deleteFansOutToBothLogs() = runTest {
        assertEquals(true, repository.deleteCallsFor("0401234567"))
        assertEquals(listOf("0401234567"), system.deletedNumbers)
        assertEquals(listOf("0401234567"), whatsApp.deletedNumbers)
    }
}
