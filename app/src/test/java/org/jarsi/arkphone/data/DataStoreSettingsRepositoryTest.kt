package org.jarsi.arkphone.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.createRepository(): DataStoreSettingsRepository {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        ) { File(tmp.root, "settings.preferences_pb") }
        return DataStoreSettingsRepository(dataStore)
    }

    @Test
    fun announceCallerDefaultsToOff() = runTest {
        assertFalse(createRepository().settings.first().announceCaller)
    }

    @Test
    fun setAnnounceCallerPersists() = runTest {
        val repository = createRepository()
        repository.setAnnounceCaller(true)
        assertTrue(repository.settings.first().announceCaller)
    }
}
