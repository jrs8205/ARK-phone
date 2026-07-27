package org.jarsi.arkphone.data

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeSystemBlockedNumberList(
    entries: List<String> = emptyList(),
) : SystemBlockedNumberList {
    val entries = entries.toMutableList()
    override suspend fun numbers(): List<String> = entries.toList()
    override suspend fun remove(number: String) {
        entries.remove(number)
    }
}

class BlockedNumbersMigrationTest {

    @Test
    fun movesSystemEntriesIntoTheAppListAndClearsThem() = runTest {
        val system = FakeSystemBlockedNumberList(listOf("+358445552841", "0401234567"))
        val settings = FakeSettingsRepository()
        BlockedNumbersMigration(system, settings).migrate()
        assertEquals(
            setOf("+358445552841", "0401234567"),
            settings.state.value.blockedNumbers,
        )
        assertEquals(emptyList<String>(), system.entries)
    }

    @Test
    fun anEmptySystemListIsANoOp() = runTest {
        val settings = FakeSettingsRepository()
        BlockedNumbersMigration(FakeSystemBlockedNumberList(), settings).migrate()
        assertEquals(emptySet<String>(), settings.state.value.blockedNumbers)
    }
}
