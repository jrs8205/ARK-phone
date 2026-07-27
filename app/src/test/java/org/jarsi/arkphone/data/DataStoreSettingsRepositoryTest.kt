package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.BlockedCallAction
import org.jarsi.arkphone.data.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        ) { File(tmp.root, "settings.preferences_pb") }

    @Test
    fun defaultsToOffWithSixSecondInterval() = runTest {
        val settings = DataStoreSettingsRepository(createDataStore()).settings.first()
        assertEquals(AnnounceMode.OFF, settings.announceMode)
        assertEquals(Settings.DEFAULT_ANNOUNCE_INTERVAL_SECONDS, settings.announceIntervalSeconds)
        assertEquals(false, settings.blockHiddenNumbers)
        assertEquals(false, settings.blockUnknownCallers)
        assertEquals(emptySet<String>(), settings.blockedPrefixes)
        assertEquals(true, settings.allowRepeatCallers)
    }

    @Test
    fun addedBlockedPrefixPersistsTrimmed() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.addBlockedPrefix(" +358700 ")
        assertEquals(setOf("+358700"), repository.settings.first().blockedPrefixes)
    }

    @Test
    fun blankPrefixIsNotAdded() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.addBlockedPrefix("   ")
        assertEquals(emptySet<String>(), repository.settings.first().blockedPrefixes)
    }

    @Test
    fun addedAllowedNumberPersistsTrimmed() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.addAllowedNumber(" 0401234567 ")
        assertEquals(setOf("0401234567"), repository.settings.first().allowedNumbers)
    }

    @Test
    fun addedBlockedNumberPersistsTrimmed() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.addBlockedNumber(" +358445552841 ")
        assertEquals(setOf("+358445552841"), repository.settings.first().blockedNumbers)
    }

    @Test
    fun announceModePersists() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.setAnnounceMode(AnnounceMode.VOICE_ONLY)
        assertEquals(AnnounceMode.VOICE_ONLY, repository.settings.first().announceMode)
    }

    @Test
    fun blockedCallActionDefaultsToReject() = runTest {
        val settings = DataStoreSettingsRepository(createDataStore()).settings.first()
        assertEquals(BlockedCallAction.REJECT, settings.blockedCallAction)
    }

    @Test
    fun blockedCallActionPersists() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.setBlockedCallAction(BlockedCallAction.VOICEMAIL)
        assertEquals(BlockedCallAction.VOICEMAIL, repository.settings.first().blockedCallAction)
    }

    // Each test performs a single DataStore write: on Windows a second
    // rename-over write to the same open store file fails with an IOException.

    @Test
    fun intervalPersists() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.setAnnounceIntervalSeconds(7)
        assertEquals(7, repository.settings.first().announceIntervalSeconds)
    }

    @Test
    fun outOfRangeIntervalIsClamped() = runTest {
        val repository = DataStoreSettingsRepository(createDataStore())
        repository.setAnnounceIntervalSeconds(99)
        assertEquals(
            Settings.MAX_ANNOUNCE_INTERVAL_SECONDS,
            repository.settings.first().announceIntervalSeconds,
        )
    }

    @Test
    fun legacyBooleanMigratesToWithRingtone() = runTest {
        val dataStore = createDataStore()
        dataStore.edit { it[booleanPreferencesKey("announce_caller")] = true }
        val repository = DataStoreSettingsRepository(dataStore)
        assertEquals(AnnounceMode.WITH_RINGTONE, repository.settings.first().announceMode)
    }

    @Test
    fun newModeKeyWinsOverLegacyBoolean() = runTest {
        val dataStore = createDataStore()
        dataStore.edit {
            it[booleanPreferencesKey("announce_caller")] = true
            it[stringPreferencesKey("announce_mode")] = AnnounceMode.OFF.name
        }
        val repository = DataStoreSettingsRepository(dataStore)
        assertEquals(AnnounceMode.OFF, repository.settings.first().announceMode)
    }
}
