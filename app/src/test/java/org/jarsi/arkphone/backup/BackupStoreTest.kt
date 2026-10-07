package org.jarsi.arkphone.backup

import java.io.IOException
import org.junit.Assert.fail
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.ArkLinkEntity
import org.jarsi.arkphone.data.ArkPhoneDatabase
import org.jarsi.arkphone.data.WhatsAppCallEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    // One DataStore write per test: on Windows a second rename-over write to
    // the same open store file fails with an IOException. Restore must
    // therefore be a single edit, which is also what keeps it atomic.
    private fun TestScope.createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job()),
        ) { File(tmp.root, "settings.preferences_pb") }

    private fun store(dataStore: DataStore<Preferences>) =
        BackupStore(dataStore, db, { 1_700_000_000_000L }, appVersion = "1.28", simAccountIds = { setOf("sim-a") })

    @After
    fun tearDown() {
        db.close()
    }

    private val link = ArkLinkEntity("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)
    private val call = WhatsAppCallEntity(
        callerName = "Alice", callerNumber = "+358401234567", type = "INCOMING",
        timestampMillis = 9L, durationSeconds = 61, isVideo = false, sourcePackage = "com.whatsapp",
    )

    @Test
    fun theSnapshotCarriesPreferencesLinksAndCallsButNotTheSyncedPushToken() = runTest {
        val dataStore = createDataStore()
        dataStore.edit {
            it[booleanPreferencesKey("announce_caller")] = true
            it[intPreferencesKey("announce_interval_seconds")] = 7
            it[stringPreferencesKey("ark_code")] = "ARK-ABCD-EFGH"
            it[stringSetPreferencesKey("blocked_prefixes")] = setOf("0700")
            it[stringPreferencesKey("ark_synced_fcm_token")] = "fcm-on-this-phone"
        }
        db.arkLinkDao().upsert(link)
        db.whatsAppCallDao().insert(call)

        val snapshot = store(dataStore).snapshot()

        assertEquals(1_700_000_000_000L, snapshot.createdAtMillis)
        assertEquals("1.28", snapshot.appVersion)
        assertEquals(
            setOf(
                BackupPreference("announce_caller", BackupPreference.Type.BOOLEAN, true),
                BackupPreference("announce_interval_seconds", BackupPreference.Type.INT, 7),
                BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-ABCD-EFGH"),
                BackupPreference("blocked_prefixes", BackupPreference.Type.STRING_SET, setOf("0700")),
            ),
            snapshot.preferences.toSet(),
        )
        assertEquals(
            listOf(BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)),
            snapshot.arkLinks,
        )
        assertEquals(
            listOf(BackupWhatsAppCall("Alice", "+358401234567", "INCOMING", 9L, 61, false, "com.whatsapp")),
            snapshot.whatsAppCalls,
        )
    }

    @Test
    fun restoreRunsTheFileThroughTheSanitizer() = runTest {
        val dataStore = createDataStore()
        val snapshot = BackupSnapshot(
            createdAtMillis = 1L,
            appVersion = "1.28",
            preferences = listOf(
                BackupPreference("announce_mode", BackupPreference.Type.STRING, "EVIL"),
                BackupPreference("announce_caller", BackupPreference.Type.BOOLEAN, true),
            ),
            arkLinks = listOf(BackupArkLink("1", "+1", "not-a-code", "Mallory", "pk", 5L)),
            whatsAppCalls = emptyList(),
        )

        store(dataStore).restore(snapshot)

        val prefs = dataStore.data.first()
        assertNull(prefs[stringPreferencesKey("announce_mode")])
        assertEquals(true, prefs[booleanPreferencesKey("announce_caller")])
        assertEquals(emptyList<ArkLinkEntity>(), db.arkLinkDao().links().first())
    }

    @Test
    fun restoreDropsSimRestrictionsThatDoNotExistOnThisPhone() = runTest {
        // A stale blocking_sim_account_id from another phone would make every
        // rule evaluate as "not this SIM" and silently switch blocking off.
        val dataStore = createDataStore()
        val snapshot = BackupSnapshot(
            1L, "1.28",
            listOf(
                BackupPreference("call_sim_account_id", BackupPreference.Type.STRING, "sim-a"),
                BackupPreference("blocking_sim_account_id", BackupPreference.Type.STRING, "sim-from-old-phone"),
            ),
            emptyList(), emptyList(),
        )

        store(dataStore).restore(snapshot)

        val prefs = dataStore.data.first()
        assertEquals("sim-a", prefs[stringPreferencesKey("call_sim_account_id")])
        assertNull(prefs[stringPreferencesKey("blocking_sim_account_id")])
    }

    @Test
    fun aFailedTableWriteLeavesThePreferencesAsTheyWere() = runTest {
        val dataStore = createDataStore()
        val snapshot = BackupSnapshot(
            1L, "1.28",
            listOf(BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-E5HU-JVA8")),
            listOf(BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)),
            emptyList(),
        )
        db.close()

        try {
            store(dataStore).restore(snapshot)
            fail("expected the closed database to fail the restore")
        } catch (e: IllegalStateException) {
            // Room refuses a closed database.
        }

        assertNull(dataStore.data.first()[stringPreferencesKey("ark_code")])
    }

    @Test
    fun aFailedPreferencesWriteRestoresTheTablesItHadReplaced() = runTest {
        val dataStore = createDataStore()
        db.arkLinkDao().upsert(link)
        db.whatsAppCallDao().insert(call)
        val failing = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        val snapshot = BackupSnapshot(
            1L, "1.28", emptyList(),
            listOf(BackupArkLink("1", "+1", "ARK-AAAA-AAAA", "Other", "pk", 1L)),
            emptyList(),
        )

        try {
            BackupStore(failing, db, { 1L }, "1.28", { setOf("sim-a") }).restore(snapshot)
            fail("expected IOException")
        } catch (e: IOException) {
            // The preferences write failed after the tables were replaced.
        }

        assertEquals(listOf(link), db.arkLinkDao().links().first())
        assertEquals(listOf(call.copy(id = db.whatsAppCallDao().callsOnce().single().id)), db.whatsAppCallDao().callsOnce())
    }

    @Test
    fun aRestoreFinishesEvenWhenItsCallerIsCancelled() = runTest {
        // Leaving the Settings screen cancels the view model's scope; a
        // half-applied restore must not be the result.
        val dataStore = createDataStore()
        val snapshot = BackupSnapshot(
            1L, "1.28",
            listOf(BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-E5HU-JVA8")),
            listOf(BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)),
            emptyList(),
        )

        val job = launch { store(dataStore).restore(snapshot) }
        runCurrent()
        job.cancel()
        job.join()

        assertEquals("ARK-E5HU-JVA8", dataStore.data.first()[stringPreferencesKey("ark_code")])
        assertEquals(listOf(link), db.arkLinkDao().links().first())
    }

    @Test
    fun theSnapshotKeepsOnlyTheNewestWhatsAppCalls() = runTest {
        // Export must never produce a file this same version refuses to read.
        val dataStore = createDataStore()
        db.whatsAppCallDao().insertAll((1..(BackupSanitizer.MAX_CALLS + 5)).map { call.copy(timestampMillis = it.toLong()) })

        val snapshot = store(dataStore).snapshot()

        assertEquals(BackupSanitizer.MAX_CALLS, snapshot.whatsAppCalls.size)
        assertEquals((BackupSanitizer.MAX_CALLS + 5).toLong(), snapshot.whatsAppCalls.first().timestampMillis)
        assertEquals(6L, snapshot.whatsAppCalls.last().timestampMillis)
    }

    @Test
    fun restoreReplacesPreferencesLinksAndCallsInOneGo() = runTest {
        val dataStore = createDataStore()
        db.arkLinkDao().upsert(link.copy(numberKey = "111", code = "ARK-OLD1-OLD1"))
        db.whatsAppCallDao().insert(call.copy(callerName = "Old"))
        val snapshot = BackupSnapshot(
            createdAtMillis = 1L,
            appVersion = "1.28",
            preferences = listOf(
                BackupPreference("announce_caller", BackupPreference.Type.BOOLEAN, false),
                BackupPreference("announce_interval_seconds", BackupPreference.Type.INT, 5),
                BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-NEW2-NEW2"),
                BackupPreference("blocked_prefixes", BackupPreference.Type.STRING_SET, setOf("0600", "0700")),
                // A crafted file must not be able to plant another phone's push token.
                BackupPreference("ark_synced_fcm_token", BackupPreference.Type.STRING, "fcm-from-old-phone"),
            ),
            arkLinks = listOf(BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)),
            whatsAppCalls = listOf(BackupWhatsAppCall("Alice", "+358401234567", "INCOMING", 9L, 61, false, "com.whatsapp")),
        )

        store(dataStore).restore(snapshot)

        val prefs = dataStore.data.first()
        assertEquals(false, prefs[booleanPreferencesKey("announce_caller")])
        assertEquals(5, prefs[intPreferencesKey("announce_interval_seconds")])
        assertEquals("ARK-NEW2-NEW2", prefs[stringPreferencesKey("ark_code")])
        assertEquals(setOf("0600", "0700"), prefs[stringSetPreferencesKey("blocked_prefixes")])
        assertNull(prefs[stringPreferencesKey("ark_synced_fcm_token")])
        assertEquals(4, prefs.asMap().size)
        assertEquals(listOf(link), db.arkLinkDao().links().first())
        assertEquals(
            listOf(call.copy(id = db.whatsAppCallDao().callsOnce().single().id)),
            db.whatsAppCallDao().callsOnce(),
        )
    }
}
