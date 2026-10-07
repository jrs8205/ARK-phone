package org.jarsi.arkphone.backup

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
        BackupStore(dataStore, db, { 1_700_000_000_000L }, appVersion = "1.28")

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
                BackupPreference("some_long", BackupPreference.Type.LONG, 1L shl 40),
                BackupPreference("some_float", BackupPreference.Type.FLOAT, 1.5f),
                BackupPreference("some_double", BackupPreference.Type.DOUBLE, 2.25),
                BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-NEW1-NEW1"),
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
        assertEquals("ARK-NEW1-NEW1", prefs[stringPreferencesKey("ark_code")])
        assertEquals(setOf("0600", "0700"), prefs[stringSetPreferencesKey("blocked_prefixes")])
        assertNull(prefs[stringPreferencesKey("ark_synced_fcm_token")])
        assertEquals(7, prefs.asMap().size)
        assertEquals(listOf(link), db.arkLinkDao().links().first())
        assertEquals(
            listOf(call.copy(id = db.whatsAppCallDao().callsOnce().single().id)),
            db.whatsAppCallDao().callsOnce(),
        )
    }
}
