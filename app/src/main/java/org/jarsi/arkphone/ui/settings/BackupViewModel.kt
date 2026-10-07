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
import org.jarsi.arkphone.backup.BackupStore
import org.jarsi.arkphone.di.IoDispatcher
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

data class PendingRestore(val uri: Uri, val encrypted: Boolean)

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
) : ViewModel() {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        store: BackupStore,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(context.contentResolver, store, BackupCodec(), io, BackupCodec.DEFAULT_ITERATIONS)

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    fun suggestedFileName(): String =
        "ARK-phone-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.arkbackup"

    /** [password] null writes the file in the clear; the screen has warned. */
    fun export(uri: Uri, password: String?) {
        run {
            val bytes = codec.encode(store.snapshot(), password, iterations)
            withContext(io) {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw IOException("no stream for $uri")
            }
            BackupMessage.Saved
        }
    }

    /** Reads only the envelope, so the screen knows whether to ask for a password. */
    fun chooseRestore(uri: Uri) {
        _uiState.update { it.copy(pendingRestore = null) }
        run {
            val info = codec.inspect(read(uri))
            _uiState.update { it.copy(pendingRestore = PendingRestore(uri, info.encrypted)) }
            null
        }
    }

    fun restore(password: String?) {
        val pending = _uiState.value.pendingRestore ?: return
        run {
            store.restore(codec.decode(read(pending.uri), password))
            _uiState.update { it.copy(pendingRestore = null) }
            BackupMessage.Restored
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun read(uri: Uri): ByteArray = withContext(io) {
        val stream = contentResolver.openInputStream(uri) ?: throw IOException("no stream for $uri")
        stream.use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                // Past the cap the file cannot be ours; stop before it eats the heap.
                if (buffer.size() > MAX_FILE_BYTES) throw BackupException(BackupError.NotABackup)
            }
            buffer.toByteArray()
        }
    }

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

    private companion object {
        const val MAX_FILE_BYTES = 16 * 1024 * 1024
    }
}
