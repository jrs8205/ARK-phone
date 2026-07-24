package org.jarsi.arkphone.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCacheTest {

    @Test
    fun currentReflectsTheStoredSettingsOnceTheScopeRuns() = runTest {
        val repository = FakeSettingsRepository(Settings(announceMode = AnnounceMode.VOICE_ONLY))
        val cache = SettingsCache(repository, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        assertEquals(AnnounceMode.VOICE_ONLY, cache.current.announceMode)
    }

    @Test
    fun awaitReturnsTheRealValueEvenWhenTheSyncViewStillHoldsDefaults() = runTest {
        val repository = FakeSettingsRepository(Settings(announceMode = AnnounceMode.VOICE_ONLY))
        val cache = SettingsCache(repository, CoroutineScope(StandardTestDispatcher(testScheduler)))
        // The cold-start bug in one line: nothing has been dispatched yet, so
        // the synchronous view is still the default...
        assertEquals(AnnounceMode.OFF, cache.current.announceMode)
        // ...but await suspends until the store has actually been read.
        assertEquals(AnnounceMode.VOICE_ONLY, cache.await().announceMode)
    }

    @Test
    fun currentFollowsLaterChanges() = runTest {
        val repository = FakeSettingsRepository(Settings(announceMode = AnnounceMode.OFF))
        val cache = SettingsCache(repository, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        repository.setAnnounceMode(AnnounceMode.WITH_RINGTONE)
        assertEquals(AnnounceMode.WITH_RINGTONE, cache.current.announceMode)
    }
}
