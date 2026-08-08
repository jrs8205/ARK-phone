package org.jarsi.arkphone.ui.contactcard

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.jarsi.arkphone.R

/**
 * Enter-or-paste a code, then confirm the nickname the worker returned. The
 * confirmation step is what makes a mistyped code visible before it is stored.
 */
@Composable
fun ArkLinkDialog(
    uiState: ContactCardUiState,
    onCodeEntered: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val pending = uiState.arkPending
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.contact_card_ark_link)) },
        text = {
            if (pending == null) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.ark_link_hint)) },
                    isError = uiState.arkError != null,
                    supportingText = {
                        when (uiState.arkError) {
                            ArkLinkError.INVALID_CODE ->
                                Text(
                                    stringResource(R.string.ark_link_invalid),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            ArkLinkError.NOT_FOUND, ArkLinkError.LOOKUP_FAILED ->
                                Text(
                                    stringResource(R.string.ark_link_not_found),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            null -> Unit
                        }
                    },
                )
            } else {
                Text(pending.nickname, style = MaterialTheme.typography.headlineSmall)
            }
        },
        confirmButton = {
            if (pending == null) {
                TextButton(
                    onClick = { onCodeEntered(code) },
                    enabled = !uiState.arkLookingUp,
                ) {
                    Text(stringResource(R.string.contact_card_ark_link))
                }
            } else {
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.ark_link_confirm, pending.nickname))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
