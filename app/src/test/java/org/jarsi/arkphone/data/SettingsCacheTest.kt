package org.jarsi.arkphone.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
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
    fun awaiterSuspendedBeforeTheFirstEmissionGetsTheEmittedValue() = runTest {
        // Encodes the v1.4.1 regression: completing the loaded-signal resumes
        // the awaiter undispatched, so the value must be stored before the
        // signal or the awaiter reads the default.
        val emissions = MutableSharedFlow<Settings>()
        val repository = object : SettingsRepository {
            override val settings: Flow<Settings> = emissions
            override suspend fun setAnnounceMode(mode: AnnounceMode) = Unit
            override suspend fun setAnnounceIntervalSeconds(seconds: Int) = Unit
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val cache = SettingsCache(repository, CoroutineScope(dispatcher))
        var awaited: Settings? = null
        launch(dispatcher) { awaited = cache.await() }
        emissions.emit(Settings(announceMode = AnnounceMode.VOICE_ONLY))
        assertEquals(AnnounceMode.VOICE_ONLY, awaited?.announceMode)
    }

    @Test
    fun currentFollowsLaterChanges() = runTest {
        val repository = FakeSettingsRepository(Settings(announceMode = AnnounceMode.OFF))
        val cache = SettingsCache(repository, CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        repository.setAnnounceMode(AnnounceMode.WITH_RINGTONE)
        assertEquals(AnnounceMode.WITH_RINGTONE, cache.current.announceMode)
    }
}
