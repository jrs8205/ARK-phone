package org.jarsi.arkphone.ui.settings

import java.util.concurrent.Executors
import org.jarsi.arkphone.telecom.CallHandle
import org.jarsi.arkphone.telecom.CallController
import kotlinx.coroutines.asCoroutineDispatcher
import android.app.Application
import android.database.sqlite.SQLiteException
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jarsi.arkphone.backup.BackupArkLink
import org.jarsi.arkphone.backup.BackupCodec
import org.jarsi.arkphone.backup.BackupError
import org.jarsi.arkphone.backup.BackupPreference
import org.jarsi.arkphone.backup.BackupSnapshot
import org.jarsi.arkphone.backup.BackupStore
import org.jarsi.arkphone.backup.RestoreJournal
import org.jarsi.arkphone.data.TableWriteLock
import org.jarsi.arkphone.data.ArkPhoneDatabase
import org.jarsi.arkphone.testing.InMemoryPreferencesDataStore
import org.jarsi.arkphone.voip.ArkCallAdmission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val dispatcher = StandardTestDispatcher()

    // Room's own executors would finish on real threads the test scheduler
    // cannot wait for; on the test dispatcher advanceUntilIdle covers them.
    private val db = Room.inMemoryDatabaseBuilder(context, ArkPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(dispatcher.asExecutor())
        .setTransactionExecutor(dispatcher.asExecutor())
        .build()
    // In memory: the restore writes the preferences inside the Room
    // transaction, whose thread here is the test thread — a file-backed
    // store would need that same thread to run its own writer.
    private val dataStore = InMemoryPreferencesDataStore()
    private val store by lazy { BackupStore(dataStore, db, { 42L }, appVersion = "1.28", simAccountIds = { emptySet() }, journal = RestoreJournal(tmp.newFolder()), lock = TableWriteLock(), io = dispatcher) }
    private val callController = CallController()
    private val admission = FakeAdmission()
    private val codec = BackupCodec()

    private class FakeAdmission : ArkCallAdmission {
        var liveCall = false
        var held = false
        val events = mutableListOf<String>()
        override fun holdForRestore(): Boolean {
            if (liveCall) return false
            held = true
            events += "hold"
            return true
        }
        override suspend fun releaseRestoreHold() {
            held = false
            events += "release"
        }
    }

    private fun viewModel(store: BackupStore = this.store) = BackupViewModel(
        context.contentResolver, store, codec, dispatcher,
        iterations = 1_000, callController = callController, admission = admission,
        maxFileBytes = 16 * 1024 * 1024,
    )

    private val identityFile: BackupSnapshot = BackupSnapshot(
        1L, "1.28",
        listOf(
            BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-NEW2-NEW2"),
            BackupPreference("ark_nickname", BackupPreference.Type.STRING, "Jarsi"),
            BackupPreference("ark_device_token", BackupPreference.Type.STRING, "tok"),
        ),
        listOf(
            BackupArkLink("445552841", "+358 44 5552841", "ARK-E5HU-JVA8", "A", "pk", 5L),
            BackupArkLink("401234567", "0401234567", "ARK-S3CU-DBNH", "B", "pk", 6L),
        ),
        emptyList(),
    )

    private fun file(snapshot: BackupSnapshot, password: String? = null): Uri =
        Uri.fromFile(File(tmp.root, "backup.arkbackup").apply { writeBytes(codec.encode(snapshot, password, iterations = 1_000)) })

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun exportWritesTheChosenFileAndReportsSaved() = runTest(dispatcher) {
        dataStore.edit { it[stringPreferencesKey("ark_code")] = "ARK-ABCD-EFGH" }
        val file = File(tmp.root, "backup.arkbackup")
        val viewModel = viewModel()

        viewModel.export(Uri.fromFile(file), password = "pw")
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Saved, viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.busy)
        val snapshot = codec.decode(file.readBytes(), password = "pw")
        assertTrue(snapshot.preferences.contains(BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-ABCD-EFGH")))
    }

    @Test
    fun choosingAnEncryptedFileAsksForItsPasswordBeforeDecoding() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.chooseRestore(file(BackupSnapshot(1L, "1.28", emptyList(), emptyList(), emptyList()), "pw"))
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.pendingRestore?.encrypted)

        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()
        assertEquals(BackupMessage.Failed(BackupError.PasswordRequired), viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.pendingRestore?.preview)
    }

    @Test
    fun decodingShowsWhatTheFileBringsBeforeAnythingIsApplied() = runTest(dispatcher) {
        // A foreign file must be recognisable in the confirmation, by the
        // ARK code it would install; nothing is touched until confirmed.
        val viewModel = viewModel()

        viewModel.chooseRestore(file(identityFile))
        testScheduler.advanceUntilIdle()
        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()

        assertEquals(RestorePreview("ARK-NEW2-NEW2", "Jarsi", linkedContacts = 2), viewModel.uiState.value.pendingRestore?.preview)
        assertFalse(viewModel.uiState.value.busy)
        assertNull(viewModel.uiState.value.message)
        assertNull(dataStore.data.first()[stringPreferencesKey("ark_code")])
        assertTrue(db.arkLinkDao().all().isEmpty())

        viewModel.applyRestore()
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Restored, viewModel.uiState.value.message)
        assertNull(viewModel.uiState.value.pendingRestore)
        assertEquals("ARK-NEW2-NEW2", dataStore.data.first()[stringPreferencesKey("ark_code")])
        assertEquals(2, db.arkLinkDao().all().size)
    }

    @Test
    fun thePreviewShowsTheIdentityTheSanitizerWillActuallyKeep() = runTest(dispatcher) {
        // An identity the sanitizer drops (here: a token the worker could
        // not have issued) must not be promised in the confirmation.
        val viewModel = viewModel()
        val snapshot = identityFile.copy(
            preferences = identityFile.preferences.map {
                if (it.key == "ark_device_token") it.copy(value = "tok\ncontrol") else it
            },
            arkLinks = emptyList(),
        )

        viewModel.chooseRestore(file(snapshot))
        testScheduler.advanceUntilIdle()
        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()

        assertEquals(RestorePreview(arkCode = null, nickname = "", linkedContacts = 0), viewModel.uiState.value.pendingRestore?.preview)
    }

    @Test
    fun confirmingClosesThePreviewAndCancelCannotStopTheApply() = runTest(dispatcher) {
        // The dialog is driven by the preview: left open during the apply,
        // its Cancel only forgot a snapshot the apply no longer reads.
        val viewModel = viewModel()
        viewModel.chooseRestore(file(identityFile))
        testScheduler.advanceUntilIdle()
        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()

        viewModel.applyRestore()
        assertNull(viewModel.uiState.value.pendingRestore?.preview)
        assertTrue(viewModel.uiState.value.busy)
        viewModel.dismissRestorePreview()
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Restored, viewModel.uiState.value.message)
        assertEquals("ARK-NEW2-NEW2", dataStore.data.first()[stringPreferencesKey("ark_code")])
    }

    @Test
    fun dismissingThePreviewForgetsTheDecodedFile() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.chooseRestore(file(identityFile))
        testScheduler.advanceUntilIdle()
        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()

        viewModel.dismissRestorePreview()
        assertNull(viewModel.uiState.value.pendingRestore?.preview)
        viewModel.applyRestore()
        testScheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.message)
        assertNull(dataStore.data.first()[stringPreferencesKey("ark_code")])
    }

    @Test
    fun restoreIsRefusedWhileACarrierCallIsInProgress() = runTest(dispatcher) {
        // Swapping the identity under a live call would strand its frames.
        callController.onCallAdded(object : CallHandle {
            override val id: String = "call-1"
            override var telecomState: Int = android.telecom.Call.STATE_ACTIVE
            override val number: String? = "0401234567"
            override val displayName: String? = null
            override val connectTimeMillis: Long = 0
            override val simAccountId: String? = null
            override fun answer() = Unit
            override fun reject() = Unit
            override fun disconnect() = Unit
            override fun hold() = Unit
            override fun unhold() = Unit
            override fun playDtmf(digit: Char) = Unit
            override fun stopDtmf() = Unit
        })
        val viewModel = viewModel()

        viewModel.chooseRestore(file(identityFile))
        testScheduler.advanceUntilIdle()
        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()
        viewModel.applyRestore()
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Failed(BackupError.CallInProgress), viewModel.uiState.value.message)
        assertNull(dataStore.data.first()[stringPreferencesKey("ark_code")])
        assertEquals(listOf("hold", "release"), admission.events)
    }

    @Test
    fun restoreIsRefusedWhileAnArkCallIsLive() = runTest(dispatcher) {
        // A ringing ARK call is not in CallController yet; the coordinator
        // is the one that knows, and it refuses the hold.
        admission.liveCall = true
        val viewModel = viewModel()

        viewModel.chooseRestore(file(identityFile))
        testScheduler.advanceUntilIdle()
        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()
        viewModel.applyRestore()
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Failed(BackupError.CallInProgress), viewModel.uiState.value.message)
        assertNull(dataStore.data.first()[stringPreferencesKey("ark_code")])
        assertTrue(admission.events.isEmpty())
    }

    @Test
    fun arkCallsAreHeldOffForTheWholeApplyAndReleasedAfterAFailure() = runTest(dispatcher) {
        // The SIM query runs inside the apply, after the call check.
        var heldDuringApply: Boolean? = null
        val failingStore = BackupStore(
            dataStore, db, { 42L }, "1.28",
            simAccountIds = {
                heldDuringApply = admission.held
                throw IOException("sim query failed")
            },
            journal = RestoreJournal(tmp.newFolder()), lock = TableWriteLock(), io = dispatcher,
        )
        val viewModel = viewModel(failingStore)

        viewModel.chooseRestore(file(identityFile))
        testScheduler.advanceUntilIdle()
        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()
        viewModel.applyRestore()
        testScheduler.advanceUntilIdle()

        assertEquals(true, heldDuringApply)
        assertEquals(BackupMessage.Failed(BackupError.Io), viewModel.uiState.value.message)
        assertEquals(listOf("hold", "release"), admission.events)
        assertFalse(admission.held)
    }

    @Test
    fun aDatabaseFailureDuringTheApplyIsReportedNotThrown() = runTest(dispatcher) {
        // Room reports a refused write as SQLiteException, a RuntimeException
        // the message mapping did not know; it took the coroutine down.
        val failingStore = BackupStore(
            dataStore, db, { 42L }, "1.28",
            simAccountIds = { throw SQLiteException("disk I/O error") },
            journal = RestoreJournal(tmp.newFolder()), lock = TableWriteLock(), io = dispatcher,
        )
        val viewModel = viewModel(failingStore)

        viewModel.chooseRestore(file(identityFile))
        testScheduler.advanceUntilIdle()
        viewModel.decodeRestore(password = null)
        testScheduler.advanceUntilIdle()
        viewModel.applyRestore()
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Failed(BackupError.Io), viewModel.uiState.value.message)
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(listOf("hold", "release"), admission.events)
    }

    @Test
    fun anExportLargerThanTheImportLimitIsRefused() = runTest(dispatcher) {
        val file = File(tmp.root, "backup.arkbackup")
        val viewModel = BackupViewModel(
            context.contentResolver, store, codec, dispatcher,
            iterations = 1_000, callController = callController, admission = admission, maxFileBytes = 64,
        )

        viewModel.export(Uri.fromFile(file), password = null)
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Failed(BackupError.Io), viewModel.uiState.value.message)
        assertFalse(file.exists() && file.length() > 0)
    }

    @Test
    fun codecAndStoreWorkRunsOffTheMainThread() {
        // 600 000 PBKDF2 iterations on Main would freeze the screen; the
        // clock is read inside snapshot(), so its thread tells where the
        // export block ran.
        val io = Executors.newSingleThreadExecutor { Thread(it, "backup-io") }.asCoroutineDispatcher()
        var snapshotThread: String? = null
        val offMainStore = BackupStore(dataStore, db, { snapshotThread = Thread.currentThread().name; 42L }, "1.28", { emptySet() }, RestoreJournal(tmp.newFolder()), TableWriteLock(), dispatcher)
        val viewModel = BackupViewModel(
            context.contentResolver, offMainStore, codec, io,
            iterations = 1_000, callController = callController, admission = admission, maxFileBytes = 16 * 1024 * 1024,
        )
        val file = File(tmp.root, "backup.arkbackup")

        viewModel.export(Uri.fromFile(file), password = "pw")
        var waited = 0
        while (viewModel.uiState.value.message == null && waited < 500) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
            waited++
        }

        assertEquals(BackupMessage.Saved, viewModel.uiState.value.message)
        // kotlinx debug mode suffixes the thread name with the coroutine id.
        assertTrue(snapshotThread?.startsWith("backup-io") == true)
        io.close()
    }

    @Test
    fun decodingRunsOffTheMainThread() {
        // The decode is where the PBKDF2 rounds are spent.
        val io = Executors.newSingleThreadExecutor { Thread(it, "backup-io") }.asCoroutineDispatcher()
        val viewModel = BackupViewModel(
            context.contentResolver, store, codec, io,
            iterations = 1_000, callController = callController, admission = admission, maxFileBytes = 16 * 1024 * 1024,
        )
        val uri = file(identityFile, "pw")

        viewModel.chooseRestore(uri)
        var waited = 0
        while (viewModel.uiState.value.pendingRestore == null && waited < 500) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
            waited++
        }
        viewModel.decodeRestore("pw")
        waited = 0
        while (viewModel.uiState.value.pendingRestore?.preview == null && waited < 500) {
            dispatcher.scheduler.advanceUntilIdle()
            Thread.sleep(10)
            waited++
        }

        assertEquals("ARK-NEW2-NEW2", viewModel.uiState.value.pendingRestore?.preview?.arkCode)
        io.close()
    }

    @Test
    fun aFileThatIsNotABackupIsReportedWhenChosen() = runTest(dispatcher) {
        val file = File(tmp.root, "notes.txt").apply { writeText("hello") }
        val viewModel = viewModel()

        viewModel.chooseRestore(Uri.fromFile(file))
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Failed(BackupError.NotABackup), viewModel.uiState.value.message)
        assertEquals(null, viewModel.uiState.value.pendingRestore)
    }
}
