package org.jarsi.arkphone.ui.settings

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jarsi.arkphone.backup.BackupCodec
import org.jarsi.arkphone.backup.BackupError
import org.jarsi.arkphone.backup.BackupPreference
import org.jarsi.arkphone.backup.BackupSnapshot
import org.jarsi.arkphone.backup.BackupStore
import org.jarsi.arkphone.data.ArkPhoneDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

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
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(dispatcher + Job()),
    ) { File(tmp.root, "settings.preferences_pb") }
    private val store = BackupStore(dataStore, db, { 42L }, appVersion = "1.28")
    private val codec = BackupCodec()

    private fun viewModel() = BackupViewModel(context.contentResolver, store, codec, dispatcher, iterations = 1_000)

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
    fun choosingAnEncryptedFileAsksForItsPasswordBeforeRestoring() = runTest(dispatcher) {
        val snapshot = BackupSnapshot(1L, "1.28", emptyList(), emptyList(), emptyList())
        val file = File(tmp.root, "backup.arkbackup").apply { writeBytes(codec.encode(snapshot, "pw", iterations = 1_000)) }
        val viewModel = viewModel()

        viewModel.chooseRestore(Uri.fromFile(file))
        testScheduler.advanceUntilIdle()
        assertEquals(true, viewModel.uiState.value.pendingRestore?.encrypted)

        viewModel.restore(password = null)
        testScheduler.advanceUntilIdle()
        assertEquals(BackupMessage.Failed(BackupError.PasswordRequired), viewModel.uiState.value.message)
    }

    @Test
    fun restoreAppliesTheFileAndReportsRestored() = runTest(dispatcher) {
        val snapshot = BackupSnapshot(
            1L, "1.28",
            listOf(BackupPreference("ark_code", BackupPreference.Type.STRING, "ARK-NEW1-NEW1")),
            emptyList(), emptyList(),
        )
        val file = File(tmp.root, "backup.arkbackup").apply { writeBytes(codec.encode(snapshot, password = null)) }
        val viewModel = viewModel()

        viewModel.chooseRestore(Uri.fromFile(file))
        testScheduler.advanceUntilIdle()
        viewModel.restore(password = null)
        testScheduler.advanceUntilIdle()

        assertEquals(BackupMessage.Restored, viewModel.uiState.value.message)
        assertEquals("ARK-NEW1-NEW1", dataStore.data.first()[stringPreferencesKey("ark_code")])
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
