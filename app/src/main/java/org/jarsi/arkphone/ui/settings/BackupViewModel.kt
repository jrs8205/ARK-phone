package org.jarsi.arkphone.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.backup.BackupCodec
import org.jarsi.arkphone.backup.BackupError
import org.jarsi.arkphone.backup.BackupException
import org.jarsi.arkphone.backup.BackupSanitizer
import org.jarsi.arkphone.backup.BackupSnapshot
import org.jarsi.arkphone.backup.BackupStore
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.voip.ArkCallAdmission
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed interface BackupMessage {
    data object Saved : BackupMessage
    data object Restored : BackupMessage
    data class Failed(val error: BackupError) : BackupMessage
}

/** What a decoded file would install, as the sanitizer will keep it. */
data class RestorePreview(val arkCode: String?, val nickname: String, val linkedContacts: Int)

data class PendingRestore(val uri: Uri, val encrypted: Boolean, val preview: RestorePreview? = null)

data class BackupUiState(
    val busy: Boolean = false,
    val message: BackupMessage? = null,
    val pendingRestore: PendingRestore? = null,
)

@HiltViewModel
class BackupViewModel(
    private val contentResolver: ContentResolver,
    private val store: BackupStore,
    private val codec: BackupCodec,
    private val io: CoroutineDispatcher,
    private val iterations: Int,
    private val callController: CallController,
    private val admission: ArkCallAdmission,
    private val maxFileBytes: Int,
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        store: BackupStore,
        @IoDispatcher io: CoroutineDispatcher,
        callController: CallController,
        admission: ArkCallAdmission,
    ) : this(
        context.contentResolver, store, BackupCodec(), io, BackupCodec.DEFAULT_ITERATIONS,
        callController, admission, MAX_FILE_BYTES,
    )

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** The chosen file, decoded and sanitised; what [applyRestore] installs. */
    private var decoded: BackupSnapshot? = null

    fun suggestedFileName(): String =
        "ARK-phone-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.arkbackup"

    /** [password] null writes the file in the clear; the screen has warned. */
    fun export(uri: Uri, password: String?) {
        run {
            withContext(io) {
                val bytes = codec.encode(store.snapshot(), password, iterations)
                // The reader refuses anything larger; "saved" must mean restorable.
                if (bytes.size > maxFileBytes) throw BackupException(BackupError.Io)
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IOException("no stream for $uri")
                BackupMessage.Saved
            }
        }
    }

    /** Reads only the envelope, so the screen knows whether to ask for a password. */
    fun chooseRestore(uri: Uri) {
        decoded = null
        _uiState.update { it.copy(pendingRestore = null) }
        run {
            val info = withContext(io) { codec.inspect(read(uri)) }
            _uiState.update { it.copy(pendingRestore = PendingRestore(uri, info.encrypted)) }
            null
        }
    }

    /**
     * Reads, decrypts and sanitises the file so the confirmation can say
     * what it brings — a foreign file is recognisable by the ARK code it
     * would install. Nothing is applied until [applyRestore].
     */
    fun decodeRestore(password: String?) {
        val pending = _uiState.value.pendingRestore ?: return
        run {
            val snapshot = withContext(io) { BackupSanitizer.sanitize(codec.decode(read(pending.uri), password)) }
            decoded = snapshot
            _uiState.update { it.copy(pendingRestore = pending.copy(preview = snapshot.preview())) }
            null
        }
    }

    fun dismissRestorePreview() {
        decoded = null
        _uiState.update { it.copy(pendingRestore = it.pendingRestore?.copy(preview = null)) }
    }

    /**
     * Applies the decoded file. A restored identity drops the signaling
     * client, so no call may be live across it and none may start while
     * it runs: the hold is taken on the main thread, the coordinator's
     * own, before the carrier-call check, and released after the apply.
     */
    fun applyRestore() {
        val snapshot = decoded ?: return
        run {
            if (!admission.holdForRestore()) throw BackupException(BackupError.CallInProgress)
            try {
                withContext(io) {
                    if (callController.calls.value.isNotEmpty()) throw BackupException(BackupError.CallInProgress)
                    store.restore(snapshot)
                }
            } finally {
                admission.releaseRestoreHold()
            }
            decoded = null
            _uiState.update { it.copy(pendingRestore = null) }
            BackupMessage.Restored
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun BackupSnapshot.preview() = RestorePreview(
        arkCode = preferences.firstOrNull { it.key == "ark_code" }?.value as? String,
        nickname = preferences.firstOrNull { it.key == "ark_nickname" }?.value as? String ?: "",
        linkedContacts = arkLinks.size,
    )

    private fun read(uri: Uri): ByteArray {
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("no stream for $uri")
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                // Past the cap the file cannot be ours; stop before it eats the heap.
                if (buffer.size() > maxFileBytes) throw BackupException(BackupError.NotABackup)
            }
            return buffer.toByteArray()
        }
    }

    /**
     * One job at a time, on the main thread; each block moves its key
     * derivation (600 000 PBKDF2 rounds), JSON and file I/O to [io] itself.
     */
    private fun run(block: suspend () -> BackupMessage?) {
        if (_uiState.value.busy) return
        _uiState.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: BackupException) {
                BackupMessage.Failed(e.error)
            } catch (e: IOException) {
                BackupMessage.Failed(BackupError.Io)
            } catch (e: SecurityException) {
                BackupMessage.Failed(BackupError.Io)
            }
            _uiState.update { it.copy(busy = false, message = message) }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
    }
}
