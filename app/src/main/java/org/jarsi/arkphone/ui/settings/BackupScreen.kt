package org.jarsi.arkphone.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.backup.BackupError
import org.jarsi.arkphone.ui.components.rememberHaptics

@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    BackupContent(
        uiState = uiState,
        onBack = onBack,
        suggestedFileName = viewModel::suggestedFileName,
        onExport = viewModel::export,
        onChooseRestore = viewModel::chooseRestore,
        onRestore = viewModel::restore,
        onMessageShown = viewModel::dismissMessage,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupContent(
    uiState: BackupUiState,
    onBack: () -> Unit,
    suggestedFileName: () -> String = { "ARK-phone-backup.arkbackup" },
    onExport: (android.net.Uri, String?) -> Unit = { _, _ -> },
    onChooseRestore: (android.net.Uri) -> Unit = {},
    onRestore: (String?) -> Unit = {},
    onMessageShown: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val messageText = uiState.message?.let { messageText(it) }
    LaunchedEffect(uiState.message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            onMessageShown()
        }
    }

    var protect by rememberSaveable { mutableStateOf(true) }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var restorePassword by rememberSaveable { mutableStateOf("") }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }

    val mismatch = protect && repeat.isNotEmpty() && password != repeat
    val canSave = !uiState.busy && (!protect || (password.isNotEmpty() && password == repeat))
    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let { onExport(it, password.takeIf { protect }) } }
    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(onChooseRestore) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.backup_export_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(stringResource(R.string.backup_export_description), style = MaterialTheme.typography.bodyMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = protect, role = Role.Switch, onValueChange = {
                        haptics.click()
                        protect = it
                    })
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.backup_password_switch),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = protect, onCheckedChange = null)
            }
            if (protect) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.backup_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = { Text(stringResource(R.string.backup_password_repeat_hint)) },
                    singleLine = true,
                    isError = mismatch,
                    supportingText = if (mismatch) {
                        { Text(stringResource(R.string.backup_password_mismatch)) }
                    } else {
                        null
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.backup_password_note), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    stringResource(R.string.backup_unencrypted_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    haptics.click()
                    createFile.launch(suggestedFileName())
                },
                enabled = canSave,
            ) {
                Text(stringResource(if (uiState.busy) R.string.backup_working else R.string.backup_save_button))
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text(stringResource(R.string.backup_restore_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.backup_restore_description), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    haptics.click()
                    openFile.launch(arrayOf("*/*"))
                },
                enabled = !uiState.busy,
            ) {
                Text(stringResource(R.string.backup_choose_file))
            }
            val pending = uiState.pendingRestore
            if (pending != null) {
                Text(stringResource(R.string.backup_file_chosen), style = MaterialTheme.typography.bodyLarge)
                if (pending.encrypted) {
                    Text(stringResource(R.string.backup_file_encrypted), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text(stringResource(R.string.backup_password_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(
                    onClick = {
                        haptics.click()
                        confirmRestore = true
                    },
                    enabled = !uiState.busy && (!pending.encrypted || restorePassword.isNotEmpty()),
                ) {
                    Text(stringResource(if (uiState.busy) R.string.backup_working else R.string.backup_restore_button))
                }
            }
            Text("", modifier = Modifier.padding(bottom = 16.dp))
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.backup_restore_confirm_title)) },
            text = { Text(stringResource(R.string.backup_restore_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    onRestore(restorePassword.takeIf { uiState.pendingRestore?.encrypted == true })
                }) {
                    Text(stringResource(R.string.backup_restore_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun messageText(message: BackupMessage): String = when (message) {
    BackupMessage.Saved -> stringResource(R.string.backup_saved)
    BackupMessage.Restored -> stringResource(R.string.backup_restored)
    is BackupMessage.Failed -> when (val error = message.error) {
        BackupError.NotABackup -> stringResource(R.string.backup_error_not_a_backup)
        is BackupError.UnsupportedVersion -> stringResource(R.string.backup_error_version, error.version)
        BackupError.PasswordRequired -> stringResource(R.string.backup_error_password_required)
        BackupError.WrongPasswordOrDamaged -> stringResource(R.string.backup_error_wrong_password)
        BackupError.Io -> stringResource(R.string.backup_error_io)
    }
}
