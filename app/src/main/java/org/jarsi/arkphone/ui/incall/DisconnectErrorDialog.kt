package org.jarsi.arkphone.ui.incall

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import org.jarsi.arkphone.R
import org.jarsi.arkphone.telecom.DisconnectError

/** Telephony's own localized explanation for a failed call, shown as-is
 *  (e.g. "Turn off airplane mode or connect to a wireless network"). */
@Composable
fun DisconnectErrorDialog(error: DisconnectError, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(error.label ?: stringResource(R.string.call_failed_title)) },
        text = error.description?.let { description -> @Composable { Text(description) } },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
        },
    )
}
