package org.jarsi.arkphone.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreSpeedDialRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        ) { File(tmp.root, "settings.preferences_pb") }

    // Each test performs a single DataStore write: on Windows a second
    // rename-over write to the same open store file fails with an IOException.

    @Test
    fun startsEmpty() = runTest {
        val repository = DataStoreSpeedDialRepository(createDataStore())
        assertEquals(emptyMap<Int, String>(), repository.entries.first())
    }

    @Test
    fun assignmentPersists() = runTest {
        val repository = DataStoreSpeedDialRepository(createDataStore())
        repository.set(3, "+358 44 5552841")
        assertEquals(mapOf(3 to "+358 44 5552841"), repository.entries.first())
    }

    @Test
    fun clearingRunsAndKeepsAnEmptyMapEmpty() = runTest {
        // set + clear in one test would be a second write to the same open
        // store file, which fails on Windows — this covers the clear path only.
        val repository = DataStoreSpeedDialRepository(createDataStore())
        repository.clear(3)
        assertEquals(emptyMap<Int, String>(), repository.entries.first())
    }
}
