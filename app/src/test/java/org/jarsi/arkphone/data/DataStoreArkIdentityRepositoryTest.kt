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
import org.jarsi.arkphone.testing.InMemoryPreferencesDataStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DataStoreArkIdentityRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        ) { File(tmp.root, "settings.preferences_pb") }

    // One DataStore write per test: on Windows a second rename-over write to
    // the same open store file fails with an IOException.

    @Test
    fun anUnregisteredDeviceHasNoIdentity() = runTest {
        val repository = DataStoreArkIdentityRepository(createDataStore())
        assertNull(repository.identity.first())
        assertNull(repository.syncedFcmToken.first())
    }

    @Test
    fun theRegisteredIdentityRoundTrips() = runTest {
        val repository = DataStoreArkIdentityRepository(createDataStore())
        repository.save(ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-abc"))
        assertEquals(
            ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-abc"),
            repository.identity.first(),
        )
    }

    @Test
    fun theSyncedFcmTokenRoundTrips() = runTest {
        val repository = DataStoreArkIdentityRepository(InMemoryPreferencesDataStore())
        val identity = ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-abc")
        repository.save(identity)
        assertTrue(repository.markFcmTokenSynced(identity, "fcm-1"))
        assertEquals("fcm-1", repository.syncedFcmToken.first())
    }

    @Test
    fun theMarkerIsNotWrittenForAnIdentityNoLongerStored() = runTest {
        val repository = DataStoreArkIdentityRepository(InMemoryPreferencesDataStore())
        repository.save(ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-abc"))
        assertFalse(repository.markFcmTokenSynced(ArkIdentity("ARK-7K3M-Q2FP", "Jarsi", "token-old"), "fcm-1"))
        assertNull(repository.syncedFcmToken.first())
    }
}
