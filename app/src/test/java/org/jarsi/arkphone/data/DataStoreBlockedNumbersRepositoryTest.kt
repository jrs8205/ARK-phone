package org.jarsi.arkphone.data

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStoreBlockedNumbersRepositoryTest {

    @Test
    fun blockingIsAlwaysAvailable() = runTest {
        assertTrue(DataStoreBlockedNumbersRepository(FakeSettingsRepository()).canBlock())
    }

    @Test
    fun blockAddsTheNumberToTheList() = runTest {
        val settings = FakeSettingsRepository()
        val repository = DataStoreBlockedNumbersRepository(settings)
        assertTrue(repository.block("+358445552841"))
        assertEquals(setOf("+358445552841"), settings.state.value.blockedNumbers)
    }

    @Test
    fun isBlockedMatchesTheNationalAndInternationalForms() = runTest {
        val settings = FakeSettingsRepository(
            Settings(blockedNumbers = setOf("+358445552841")),
        )
        val repository = DataStoreBlockedNumbersRepository(settings)
        assertTrue(repository.isBlocked("+358445552841"))
        assertTrue(repository.isBlocked("0445552841"))
        assertFalse(repository.isBlocked("0401234567"))
    }

    @Test
    fun unblockRemovesEveryMatchingForm() = runTest {
        val settings = FakeSettingsRepository(
            Settings(blockedNumbers = setOf("+358445552841", "0401234567")),
        )
        val repository = DataStoreBlockedNumbersRepository(settings)
        assertTrue(repository.unblock("0445552841"))
        assertEquals(setOf("0401234567"), settings.state.value.blockedNumbers)
    }

    @Test
    fun unblockingAnUnknownNumberReportsFalse() = runTest {
        val repository = DataStoreBlockedNumbersRepository(FakeSettingsRepository())
        assertFalse(repository.unblock("0445552841"))
    }
}
