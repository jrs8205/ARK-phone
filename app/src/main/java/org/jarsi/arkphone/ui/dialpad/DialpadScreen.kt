package org.jarsi.arkphone.ui.dialpad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.components.clickableListItemModifier
import org.jarsi.arkphone.ui.components.rememberHaptics

@Composable
fun DialpadScreen(
    onCall: (String) -> Unit,
    viewModel: DialpadViewModel = hiltViewModel(),
    initialNumber: String? = null,
    onInitialNumberConsumed: () -> Unit = {},
    onVoicemail: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(initialNumber) {
        if (initialNumber != null) {
            viewModel.setNumber(initialNumber)
            onInitialNumberConsumed()
        }
    }
    val context = LocalContext.current
    var pendingSpeedDialDigit by rememberSaveable { mutableStateOf<Int?>(null) }
    val speedDialPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val digit = pendingSpeedDialDigit
        pendingSpeedDialDigit = null
        val uri = result.data?.data
        if (digit != null && result.resultCode == Activity.RESULT_OK && uri != null) {
            phoneNumberFromPickedUri(context, uri)?.let { viewModel.saveSpeedDial(digit, it) }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        DialpadContent(
            uiState = uiState,
            onKey = viewModel::onKey,
            onDelete = viewModel::onDelete,
            onCall = { onCall(uiState.number) },
            onSuggestion = { number -> viewModel.setNumber(number) },
            onPaste = viewModel::onPaste,
            onVoicemail = onVoicemail,
            onSpeedDial = { digit ->
                val assigned = uiState.speedDial[digit]
                if (assigned != null) {
                    onCall(assigned)
                } else {
                    pendingSpeedDialDigit = digit
                    runCatching {
                        speedDialPicker.launch(
                            Intent(
                                Intent.ACTION_PICK,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            ),
                        )
                    }
                }
            },
        )
    }
}

private fun phoneNumberFromPickedUri(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

@Composable
fun DialpadContent(
    uiState: DialpadUiState,
    onKey: (Char) -> Unit,
    onDelete: () -> Unit,
    onCall: () -> Unit,
    onSuggestion: (String) -> Unit,
    onPaste: (String) -> Unit = {},
    onVoicemail: () -> Unit = {},
    onSpeedDial: (Int) -> Unit = {},
) {
    val haptics = rememberHaptics()
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LazyColumn(Modifier.weight(1f, fill = true)) {
            items(uiState.suggestions, key = { it.id }) { contact ->
                ListItem(
                    modifier = clickableListItemModifier {
                        contact.phoneNumber?.let(onSuggestion)
                    },
                    headlineContent = { Text(contact.displayName) },
                    supportingContent = { contact.phoneNumber?.let { Text(it) } },
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pasteLabel = stringResource(R.string.dialpad_paste)
                Text(
                    text = uiState.displayNumber.ifEmpty { uiState.number },
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = {},
                            onLongClickLabel = pasteLabel,
                            onLongClick = {
                                haptics.longPress()
                                clipboard.getText()?.text?.let(onPaste)
                            },
                        ),
                )
                IconButton(
                    onClick = {
                        haptics.click()
                        onDelete()
                    },
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Backspace,
                        contentDescription = stringResource(R.string.dialpad_delete),
                    )
                }
            }
        }
        DialpadGrid(onKey = onKey, onVoicemail = onVoicemail, onSpeedDial = onSpeedDial)
        FloatingActionButton(
            onClick = {
                haptics.confirm()
                onCall()
            },
            modifier = Modifier.padding(top = 16.dp).size(72.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.dialpad_call))
        }
    }
}
