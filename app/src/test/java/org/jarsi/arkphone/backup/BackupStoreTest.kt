package org.jarsi.arkphone.backup

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
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
import org.jarsi.arkphone.data.TableWriteLock
import org.jarsi.arkphone.data.RoomWhatsAppCallLogRepository
import org.jarsi.arkphone.data.RoomArkLinkRepository
import org.jarsi.arkphone.testing.InMemoryPreferencesDataStore
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.WhatsAppCallRecord
import org.jarsi.arkphone.data.WhatsAppCallEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Framework SQLite whose writes can be made to fail on demand. */
private class FaultyWrites : SupportSQLiteOpenHelper.Factory {
    @Volatile
    var failWrites = false

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val real = FrameworkSQLiteOpenHelperFactory().create(configuration)
        return object : SupportSQLiteOpenHelper by real {
            override val writableDatabase: SupportSQLiteDatabase get() = faulty(real.writableDatabase)
            override val readableDatabase: SupportSQLiteDatabase get() = faulty(real.readableDatabase)
        }
    }

    /**
     * The next outermost exclusive transaction (Room's withTransaction; the
     * DAOs and the invalidation tracker use non-exclusive ones) rolls back
     * at its COMMIT and reports a failure, as disk-full does.
     */
    @Volatile
    var failNextCommit = false

    /** From that failed COMMIT on, every write is refused too. */
    @Volatile
    var failWritesAfterCommitFault = false

    // SQLite nests transactions per thread; a non-exclusive begin on
    // another thread blocks until the restore's connection is free, so the
    // count must be per thread too and move only after the begin returned.
    private val depth = ThreadLocal.withInitial { 0 }
    private val armed = ThreadLocal.withInitial { false }

    private fun refuse() {
        if (failWrites) throw SQLiteException("disk I/O error")
    }

    private fun faulty(db: SupportSQLiteDatabase): SupportSQLiteDatabase = object : SupportSQLiteDatabase by db {
        override fun beginTransaction() {
            db.beginTransaction()
            if (depth.get() == 0 && failNextCommit) {
                failNextCommit = false
                armed.set(true)
            }
            depth.set(depth.get() + 1)
        }
        override fun beginTransactionNonExclusive() {
            db.beginTransactionNonExclusive()
            depth.set(depth.get() + 1)
        }
        override fun setTransactionSuccessful() {
            if (armed.get() && depth.get() == 1) return
            db.setTransactionSuccessful()
        }
        override fun endTransaction() {
            depth.set(depth.get() - 1)
            db.endTransaction()
            if (armed.get() && depth.get() == 0) {
                armed.set(false)
                if (failWritesAfterCommitFault) failWrites = true
                throw SQLiteException("disk I/O error")
            }
        }
        override fun compileStatement(sql: String): SupportSQLiteStatement {
            val statement = db.compileStatement(sql)
            return object : SupportSQLiteStatement by statement {
                override fun execute() {
                    refuse()
                    statement.execute()
                }
                override fun executeInsert(): Long {
                    refuse()
                    return statement.executeInsert()
                }
                override fun executeUpdateDelete(): Int {
                    refuse()
                    return statement.executeUpdateDelete()
                }
            }
        }
        override fun execSQL(sql: String) {
            refuse()
            db.execSQL(sql)
        }
        override fun execSQL(sql: String, bindArgs: Array<out Any?>) {
            refuse()
            db.execSQL(sql, bindArgs)
        }
        override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int {
            refuse()
            return db.delete(table, whereClause, whereArgs)
        }
        override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long {
            refuse()
            return db.insert(table, conflictAlgorithm, values)
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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

    private val journalDir: File by lazy { tmp.newFolder("no_backup") }
    private val lock = TableWriteLock {}

    private fun store(dataStore: DataStore<Preferences>, database: ArkPhoneDatabase = db) =
        BackupStore(dataStore, database, { 1_700_000_000_000L }, appVersion = "1.28", simAccountIds = { setOf("sim-a") }, journal = RestoreJournal(journalDir), lock = lock, io = Dispatchers.IO)

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
        val faults = FaultyWrites()
        val faultyDb = Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
            .allowMainThreadQueries()
            .openHelperFactory(faults)
            .build()
        val snapshot = BackupSnapshot(
            1L, "1.28",
            listOf(BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-E5HU-JVA8")),
            listOf(BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "Jarsi", "pk", 5L)),
            emptyList(),
        )
        faults.failWrites = true

        try {
            BackupStore(dataStore, faultyDb, { 1L }, "1.28", { setOf("sim-a") }, RestoreJournal(journalDir), lock, Dispatchers.IO).restore(snapshot)
            fail("expected the refused table write to fail the restore")
        } catch (e: SQLiteException) {
            // The tables could not be replaced; nothing else may change.
        }

        assertNull(dataStore.data.first()[stringPreferencesKey("ark_code")])
        faultyDb.close()
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
            BackupStore(failing, db, { 1L }, "1.28", { setOf("sim-a") }, RestoreJournal(journalDir), lock, Dispatchers.IO).restore(snapshot)
            fail("expected IOException")
        } catch (e: IOException) {
            // The preferences write failed after the tables were replaced.
        }

        assertEquals(listOf(link), db.arkLinkDao().links().first())
        assertEquals(listOf(call.copy(id = db.whatsAppCallDao().callsOnce().single().id)), db.whatsAppCallDao().callsOnce())
    }

    @Test
    fun theOriginalRowsSurviveAPreferencesFailureEvenWhenTheDatabaseRefusesToWriteAgain() = runTest {
        // A compensating table write after a failed preferences write can
        // itself fail (disk full hits both stores); the original rows must
        // not have been the only copy that is gone by then.
        val faults = FaultyWrites()
        val faultyDb = Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
            .allowMainThreadQueries()
            .openHelperFactory(faults)
            .build()
        faultyDb.arkLinkDao().upsert(link)
        faultyDb.whatsAppCallDao().insert(call)
        val dataStore = createDataStore()
        val failing = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                faults.failWrites = true
                throw IOException("disk full")
            }
        }
        val snapshot = BackupSnapshot(
            1L, "1.28", emptyList(),
            listOf(BackupArkLink("1", "+1", "ARK-AAAA-AAAA", "Other", "pk", 1L)),
            listOf(BackupWhatsAppCall("Imported", "+358401234567", "INCOMING", 9L, 61, false, "com.whatsapp")),
        )

        try {
            BackupStore(failing, faultyDb, { 1L }, "1.28", { setOf("sim-a") }, RestoreJournal(journalDir), lock, Dispatchers.IO).restore(snapshot)
            fail("expected IOException")
        } catch (e: IOException) {
            // The preferences write failed after the tables were replaced.
        }
        faults.failWrites = false

        assertEquals(listOf(link), faultyDb.arkLinkDao().links().first())
        assertEquals(listOf("Alice"), faultyDb.whatsAppCallDao().callsOnce().map { it.callerName })
        faultyDb.close()
    }

    @Test
    fun aCallRecordedWhileTheRestoreIsWritingIsNeverRolledAway() = runTest {
        // WhatsAppCallMonitor records a call on its own thread whenever one
        // ends; one that lands while the restore is between its two stores
        // must survive the restore's failure.
        val dataStore = createDataStore()
        db.whatsAppCallDao().insert(call)
        val monitor = CoroutineScope(Dispatchers.IO + Job())
        val recorded = CountDownLatch(1)
        var recording: Job? = null
        val failing = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                recording = monitor.launch {
                    db.whatsAppCallDao().insert(call.copy(callerName = "Live"))
                    recorded.countDown()
                }
                // Gives the monitor its chance; a store that keeps it out
                // until the outcome is known simply times out here.
                recorded.await(300, TimeUnit.MILLISECONDS)
                throw IOException("disk full")
            }
        }
        val snapshot = BackupSnapshot(
            1L, "1.28", emptyList(), emptyList(),
            listOf(BackupWhatsAppCall("Imported", "+358401234567", "INCOMING", 9L, 61, false, "com.whatsapp")),
        )

        try {
            BackupStore(failing, db, { 1L }, "1.28", { setOf("sim-a") }, RestoreJournal(journalDir), lock, Dispatchers.IO).restore(snapshot)
            fail("expected IOException")
        } catch (e: IOException) {
            // The preferences write failed after the tables were replaced.
        }
        recording!!.join()

        assertEquals(setOf("Alice", "Live"), db.whatsAppCallDao().callsOnce().map { it.callerName }.toSet())
    }

    private fun faultyDatabase(faults: FaultyWrites): ArkPhoneDatabase =
        Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
            .allowMainThreadQueries()
            .openHelperFactory(faults)
            .build()

    private val importedFile = BackupSnapshot(
        1L, "1.28",
        listOf(BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-NEW2-NEW2")),
        listOf(BackupArkLink("1", "+1", "ARK-AAAA-AAAA", "Other", "pk", 1L)),
        listOf(BackupWhatsAppCall("Imported", "+358401234567", "INCOMING", 9L, 61, false, "com.whatsapp")),
    )

    @Test
    fun aFailedCommitAfterThePreferencesWriteStillCompletesTheRestore() = runTest {
        // Disk-full at the final COMMIT: the preferences file is already
        // renamed into place, the tables roll back. The restore must end
        // with both stores holding the file, not half of each.
        val faults = FaultyWrites()
        val faultyDb = faultyDatabase(faults)
        faultyDb.arkLinkDao().upsert(link)
        val dataStore = createDataStore()
        faults.failNextCommit = true

        store(dataStore, faultyDb).restore(importedFile)

        assertEquals("ARK-NEW2-NEW2", dataStore.data.first()[stringPreferencesKey("ark_code")])
        assertEquals(listOf("1"), faultyDb.arkLinkDao().all().map { it.numberKey })
        assertEquals(listOf("Imported"), faultyDb.whatsAppCallDao().callsOnce().map { it.callerName })
        assertFalse(File(journalDir, "restore.journal").exists())
        faultyDb.close()
    }

    @Test
    fun aFailedCommitWhoseRetryFailsTooIsFinishedAtTheNextStart() = runTest {
        val faults = FaultyWrites()
        val faultyDb = faultyDatabase(faults)
        faultyDb.arkLinkDao().upsert(link)
        val dataStore = createDataStore()
        faults.failNextCommit = true
        faults.failWritesAfterCommitFault = true // the immediate retry fails as well

        try {
            store(dataStore, faultyDb).restore(importedFile)
            fail("expected SQLiteException")
        } catch (e: SQLiteException) {
            // Preferences new, tables old — and a journal that says so.
        }
        assertEquals("ARK-NEW2-NEW2", dataStore.data.first()[stringPreferencesKey("ark_code")])
        assertEquals(listOf(link), faultyDb.arkLinkDao().all())
        assertTrue(File(journalDir, "restore.journal").exists())

        faults.failWrites = false
        store(dataStore, faultyDb).recoverInterruptedRestore()

        assertEquals(listOf("1"), faultyDb.arkLinkDao().all().map { it.numberKey })
        assertEquals(listOf("Imported"), faultyDb.whatsAppCallDao().callsOnce().map { it.callerName })
        assertFalse(File(journalDir, "restore.journal").exists())
        faultyDb.close()
    }

    @Test
    fun aJournalWhosePreferencesNeverCommittedIsDiscarded() = runTest {
        // A process death before the preferences rename: SQLite rolled the
        // tables back on reopen, the preferences are untouched — the
        // journal must not replay the tables over them.
        val dataStore = createDataStore()
        db.arkLinkDao().upsert(link)
        RestoreJournal(journalDir).write("never-committed", importedFile)

        store(dataStore).recoverInterruptedRestore()

        assertEquals(listOf(link), db.arkLinkDao().all())
        assertNull(dataStore.data.first()[stringPreferencesKey("ark_code")])
        assertFalse(File(journalDir, "restore.journal").exists())
    }

    @Test
    fun aCleanlyFailedRestoreLeavesNoJournal() = runTest {
        val dataStore = createDataStore()
        val failing = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }

        try {
            BackupStore(failing, db, { 1L }, "1.28", { setOf("sim-a") }, RestoreJournal(journalDir), lock, Dispatchers.IO).restore(importedFile)
            fail("expected IOException")
        } catch (e: IOException) {
            // SQLite rolled back; nothing is left to finish later.
        }

        assertFalse(File(journalDir, "restore.journal").exists())
    }

    @Test
    fun aNewRestoreDoesNotReplaceTheJournalOfOneItCannotFinish() = runTest {
        // Restore A: preferences committed, tables not, retry failed, the
        // next start's recovery failed too (the database still refuses).
        // Restore B must not overwrite A's journal — its own failure would
        // delete it, and A could never be finished.
        val faults = FaultyWrites()
        val faultyDb = faultyDatabase(faults)
        faultyDb.arkLinkDao().upsert(link)
        val dataStore = createDataStore()
        faults.failNextCommit = true
        faults.failWritesAfterCommitFault = true
        try {
            store(dataStore, faultyDb).restore(importedFile)
            fail("expected SQLiteException")
        } catch (e: SQLiteException) {
            // A is pending in the journal.
        }
        val second = importedFile.copy(arkLinks = listOf(BackupArkLink("2", "+2", "ARK-BBBB-BBBB", "Second", "pk", 2L)))

        try {
            store(dataStore, faultyDb).restore(second)
            fail("expected the unfinished restore to refuse a new one")
        } catch (e: SQLiteException) {
            // Still A's preferences, still A's journal.
        }
        assertEquals("ARK-NEW2-NEW2", dataStore.data.first()[stringPreferencesKey("ark_code")])
        faults.failWrites = false
        store(dataStore, faultyDb).recoverInterruptedRestore()

        assertEquals(listOf("1"), faultyDb.arkLinkDao().all().map { it.numberKey })
        assertFalse(File(journalDir, "restore.journal").exists())
        faultyDb.close()
    }

    @Test
    fun aStartupRecoveryInFlightDoesNotDiscardAJournalARestoreWritesMeanwhile() = runTest {
        // The recovery has read journal A and is waiting on the preferences
        // when the user's restore B runs: B's journal must survive the
        // recovery's clean-up of A, or B can never be finished.
        val faults = FaultyWrites()
        val faultyDb = faultyDatabase(faults)
        faultyDb.arkLinkDao().upsert(link)
        val dataStore = createDataStore()
        RestoreJournal(journalDir).write("never-committed", importedFile)
        val recoveryWaiting = CountDownLatch(1)
        val gate = CompletableDeferred<Unit>()
        val gated = object : DataStore<Preferences> by dataStore {
            override val data: Flow<Preferences> = flow {
                recoveryWaiting.countDown()
                gate.await()
                emitAll(dataStore.data)
            }
        }
        val actors = CoroutineScope(Dispatchers.IO + Job())
        val recovery = actors.launch {
            BackupStore(gated, faultyDb, { 1L }, "1.28", { setOf("sim-a") }, RestoreJournal(journalDir), lock, Dispatchers.IO)
                .recoverInterruptedRestore()
        }
        assertTrue(recoveryWaiting.await(5, TimeUnit.SECONDS))
        faults.failNextCommit = true
        faults.failWritesAfterCommitFault = true
        val second = importedFile.copy(arkLinks = listOf(BackupArkLink("2", "+2", "ARK-BBBB-BBBB", "Second", "pk", 2L)))
        val restoreB = actors.launch {
            runCatching { store(dataStore, faultyDb).restore(second) }
        }
        Thread.sleep(300)
        gate.complete(Unit)
        recovery.join()
        restoreB.join()

        assertEquals("ARK-NEW2-NEW2", dataStore.data.first()[stringPreferencesKey("ark_code")])
        faults.failWrites = false
        store(dataStore, faultyDb).recoverInterruptedRestore()

        assertEquals(listOf("2"), faultyDb.arkLinkDao().all().map { it.numberKey })
        faultyDb.close()
    }

    @Test
    fun aCallRecordedWhileTheCommitFailsLandsOnTheRestoredTables() = runTest {
        // The failed COMMIT releases SQLite's lock before the retry begins;
        // a WhatsApp call queued behind it must not be wiped by the retry.
        val faults = FaultyWrites()
        val faultyDb = faultyDatabase(faults)
        faultyDb.whatsAppCallDao().insert(call)
        val dataStore = createDataStore()
        val monitor = RoomWhatsAppCallLogRepository(faultyDb.whatsAppCallDao(), lock)
        val recorded = CountDownLatch(1)
        var recording: Job? = null
        val observing = object : DataStore<Preferences> by dataStore {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                recording = CoroutineScope(Dispatchers.IO + Job()).launch {
                    monitor.record(WhatsAppCallRecord("Live", "+358401234567", CallType.INCOMING, 10L, 5, false))
                    recorded.countDown()
                }
                // Queued behind the restore; a store that keeps it out until
                // the outcome is known simply times out here.
                recorded.await(300, TimeUnit.MILLISECONDS)
                return dataStore.updateData(transform)
            }
        }
        faults.failNextCommit = true

        BackupStore(observing, faultyDb, { 1L }, "1.28", { setOf("sim-a") }, RestoreJournal(journalDir), lock, Dispatchers.IO)
            .restore(importedFile)
        recording!!.join()

        assertEquals(setOf("Imported", "Live"), faultyDb.whatsAppCallDao().callsOnce().map { it.callerName }.toSet())
        faultyDb.close()
    }

    @Test
    fun theJournalIsReadOnTheIoDispatcher() = runTest {
        // Recovery runs from the application scope (Main.immediate); a
        // journal of thousands of rows must not be parsed on Main.
        val io = Executors.newSingleThreadExecutor { Thread(it, "backup-io") }.asCoroutineDispatcher()
        var readOn: String? = null
        val journal = object : RestoreJournal(journalDir) {
            override fun read(): Entry? {
                readOn = Thread.currentThread().name
                return super.read()
            }
        }
        journal.write("never-committed", importedFile)

        BackupStore(createDataStore(), db, { 1L }, "1.28", { setOf("sim-a") }, journal, lock, io).recoverInterruptedRestore()

        assertTrue("read on $readOn", readOn?.startsWith("backup-io") == true)
        io.close()
    }

    /** Uses the same lazy recovery wiring as the application graph. */
    private fun guardedStore(
        dataStore: DataStore<Preferences>,
        database: ArkPhoneDatabase = db,
        journal: RestoreJournal = RestoreJournal(journalDir),
    ): Pair<BackupStore, TableWriteLock> {
        lateinit var backup: BackupStore
        val guarded = TableWriteLock(dagger.Lazy { backup })
        backup = BackupStore(dataStore, database, { 1L }, "1.28", { emptySet() }, journal, guarded, Dispatchers.IO)
        return backup to guarded
    }

    @Test
    fun aCallRecordedAfterBothRestoreAttemptsFailFinishesRecoveryBeforeWriting() = runTest {
        val faults = FaultyWrites()
        val faultyDb = faultyDatabase(faults)
        try {
            faultyDb.whatsAppCallDao().insert(call)
            val (backup, guarded) = guardedStore(InMemoryPreferencesDataStore(), faultyDb)
            faults.failNextCommit = true
            faults.failWritesAfterCommitFault = true
            try {
                backup.restore(importedFile)
                fail("expected SQLiteException")
            } catch (_: SQLiteException) {
                assertTrue(RestoreJournal(journalDir).exists())
            }
            faults.failWrites = false

            RoomWhatsAppCallLogRepository(faultyDb.whatsAppCallDao(), guarded).record(
                WhatsAppCallRecord("Live", "+358401234567", CallType.INCOMING, 10L, 5, false),
            )
            backup.recoverInterruptedRestore()

            assertEquals(setOf("Imported", "Live"), faultyDb.whatsAppCallDao().callsOnce().map { it.callerName }.toSet())
            assertFalse(RestoreJournal(journalDir).exists())
        } finally {
            faultyDb.close()
        }
    }

    @Test
    fun aLinkWrittenBeforeTheStartupTaskFinishesThePendingRestoreFirst() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit { it[stringPreferencesKey("backup_restore_id")] = "pending" }
        RestoreJournal(journalDir).write("pending", importedFile)
        val (backup, guarded) = guardedStore(dataStore)

        RoomArkLinkRepository(db.arkLinkDao(), guarded).link("+2", "ARK-BBBB-BBBB", "Live", "pk", 2L)
        backup.recoverInterruptedRestore()

        assertEquals(setOf("1", "2"), db.arkLinkDao().all().map { it.numberKey }.toSet())
        assertFalse(RestoreJournal(journalDir).exists())
    }

    @Test
    fun ordinaryWritesAreRefusedWhileThePendingRestoreCannotBeRead() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit { it[stringPreferencesKey("backup_restore_id")] = "pending" }
        var refuse = true
        val journal = object : RestoreJournal(journalDir) {
            override fun read(): Entry? {
                if (refuse) throw IOException("storage unavailable")
                return super.read()
            }
        }
        journal.write("pending", importedFile)
        val (_, guarded) = guardedStore(dataStore, journal = journal)
        val repository = RoomWhatsAppCallLogRepository(db.whatsAppCallDao(), guarded)
        val live = WhatsAppCallRecord("Live", "+358401234567", CallType.INCOMING, 10L, 5, false)
        try {
            repository.record(live)
            fail("expected recovery failure to refuse the write")
        } catch (_: IOException) {
            assertTrue(db.whatsAppCallDao().callsOnce().isEmpty())
            assertTrue(journal.exists())
        }

        refuse = false
        repository.record(live)

        assertEquals(setOf("Imported", "Live"), db.whatsAppCallDao().callsOnce().map { it.callerName }.toSet())
    }

    @Test
    fun snapshotFinishesAPendingRestoreBeforeReadingEitherStore() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit {
            it[stringPreferencesKey("backup_restore_id")] = "pending"
            it[stringPreferencesKey("ark_code")] = "ARK-NEW2-NEW2"
        }
        db.arkLinkDao().upsert(link)
        RestoreJournal(journalDir).write("pending", importedFile)

        val snapshot = store(dataStore).snapshot()

        assertEquals(importedFile.preferences, snapshot.preferences)
        assertEquals(importedFile.arkLinks, snapshot.arkLinks)
        assertEquals(importedFile.whatsAppCalls, snapshot.whatsAppCalls)
        assertFalse(RestoreJournal(journalDir).exists())
    }

    @Test
    fun snapshotFailsInsteadOfExportingAnUnfinishedRestore() = runTest {
        val faults = FaultyWrites()
        val faultyDb = faultyDatabase(faults)
        try {
            faultyDb.arkLinkDao().upsert(link)
            val dataStore = InMemoryPreferencesDataStore()
            dataStore.edit { it[stringPreferencesKey("backup_restore_id")] = "pending" }
            RestoreJournal(journalDir).write("pending", importedFile)
            faults.failWrites = true

            try {
                store(dataStore, faultyDb).snapshot()
                fail("expected recovery failure to refuse the snapshot")
            } catch (_: SQLiteException) {
                assertTrue(RestoreJournal(journalDir).exists())
                assertEquals(listOf(link), faultyDb.arkLinkDao().all())
            }
        } finally {
            faultyDb.close()
        }
    }

    @Test
    fun aRestoreWaitsUntilTheWholeSnapshotHasBeenRead() = runTest {
        val dataStore = InMemoryPreferencesDataStore()
        dataStore.edit { it[stringPreferencesKey("ark_code")] = "ARK-OLD2-OLD2" }
        db.arkLinkDao().upsert(link)
        val captured = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val restoreStarted = CountDownLatch(1)
        val restoreWriting = CountDownLatch(1)
        var pause = true
        val gated = object : DataStore<Preferences> by dataStore {
            override val data: Flow<Preferences> = flow {
                val preferences = dataStore.data.first()
                if (pause) {
                    pause = false
                    captured.complete(Unit)
                    gate.await()
                }
                emit(preferences)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                restoreWriting.countDown()
                return dataStore.updateData(transform)
            }
        }
        val backup = BackupStore(gated, db, { 1L }, "1.28", {
            restoreStarted.countDown()
            emptySet()
        }, RestoreJournal(journalDir), lock, Dispatchers.IO)
        val exporting = async { backup.snapshot() }
        captured.await()
        val applying = async(Dispatchers.IO) { backup.restore(importedFile) }
        try {
            assertTrue(restoreStarted.await(5, TimeUnit.SECONDS))
            assertFalse(restoreWriting.await(300, TimeUnit.MILLISECONDS))
            assertFalse(applying.isCompleted)
            assertEquals(listOf(link), db.arkLinkDao().all())
        } finally {
            gate.complete(Unit)
        }
        val snapshot = exporting.await()
        applying.await()

        assertEquals("ARK-OLD2-OLD2", snapshot.preferences.single { it.key == "ark_code" }.value)
        assertEquals(listOf(link.numberKey), snapshot.arkLinks.map { it.numberKey })
        assertEquals(importedFile.arkLinks, backup.snapshot().arkLinks)
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
        // The restore's own id rides along, for a journal to recognise.
        assertNotNull(prefs[stringPreferencesKey("backup_restore_id")])
        assertEquals(5, prefs.asMap().size)
        assertEquals(listOf(link), db.arkLinkDao().links().first())
        assertEquals(
            listOf(call.copy(id = db.whatsAppCallDao().callsOnce().single().id)),
            db.whatsAppCallDao().callsOnce(),
        )
    }
}
