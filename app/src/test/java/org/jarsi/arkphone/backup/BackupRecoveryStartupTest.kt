package org.jarsi.arkphone.backup

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.ArkPhoneDatabase
import org.jarsi.arkphone.data.TableWriteLock
import org.jarsi.arkphone.testing.InMemoryPreferencesDataStore
import org.jarsi.arkphone.voip.ArkCallAdmission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupRecoveryStartupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    private class FakeAdmission : ArkCallAdmission {
        @Volatile
        var held = false
        val events = mutableListOf<String>()
        override fun holdForRestore(): Boolean {
            held = true
            events += "hold"
            return true
        }
        override suspend fun releaseRestoreHold() {
            held = false
            events += "release"
        }
    }

    /** A journal whose read waits for the test — the recovery in flight. */
    private class GatedJournal(dir: java.io.File) : RestoreJournal(dir) {
        val gate = CompletableDeferred<Unit>()
        var heldWhenRead: Boolean? = null
        var admission: FakeAdmission? = null
        override fun read(): Entry? {
            heldWhenRead = admission?.held
            // Bounded: a recovery that runs on the test thread would otherwise deadlock here.
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(2_000L) { gate.await() } }
            return super.read()
        }
    }

    /** Real threads: the startup's launch must take the hold before its first suspension. */
    private val startupScope = CoroutineScope(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun arkCallsAreHeldOffUntilTheRecoveryIsDone() = runTest {
        // The identity in the preferences may already be the restored one
        // while the tables, and the link cache built from them, are not.
        val admission = FakeAdmission()
        val journal = GatedJournal(tmp.newFolder()).also { it.admission = admission }
        val store = BackupStore(InMemoryPreferencesDataStore(), db, { 1L }, "1.28", { emptySet() }, journal, TableWriteLock(), Dispatchers.IO)
        val startup = BackupRecoveryStartup(store, admission, startupScope)

        startup.onAppStart()
        assertTrue(admission.held)

        journal.gate.complete(Unit)
        while (admission.held) Thread.sleep(5)

        assertEquals(true, journal.heldWhenRead)
        assertEquals(listOf("hold", "release"), admission.events)
    }

    @Test
    fun theHoldIsReleasedWhenTheRecoveryFails() = runTest {
        val admission = FakeAdmission()
        val journal = object : RestoreJournal(tmp.newFolder()) {
            override fun read(): Entry? = throw IllegalStateException("storage gone")
        }
        val store = BackupStore(InMemoryPreferencesDataStore(), db, { 1L }, "1.28", { emptySet() }, journal, TableWriteLock(), Dispatchers.IO)
        val startup = BackupRecoveryStartup(store, admission, startupScope)

        startup.onAppStart()
        var waited = 0
        while (admission.held && waited < 400) {
            Thread.sleep(5)
            waited++
        }

        assertFalse(admission.held)
        assertEquals(listOf("hold", "release"), admission.events)
    }
}
