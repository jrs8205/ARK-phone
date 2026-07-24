package org.jarsi.arkphone.ui.recents

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.ui.components.clickableListItemModifier
import org.jarsi.arkphone.ui.components.rememberHaptics

@StringRes
internal fun callTypeLabelRes(type: CallType): Int = when (type) {
    CallType.INCOMING -> R.string.call_type_incoming
    CallType.OUTGOING -> R.string.call_type_outgoing
    CallType.MISSED -> R.string.call_type_missed
    CallType.REJECTED -> R.string.call_type_rejected
    CallType.BLOCKED -> R.string.call_type_blocked
    CallType.OTHER -> R.string.call_type_other
}

@Composable
fun RecentsScreen(
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenDetails: (String) -> Unit = {},
    viewModel: RecentsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refreshPermissionState()
        onPauseOrDispose { }
    }
    RecentsContent(uiState, onCall, onRequestPermissions, onOpenDetails)
}

@Composable
fun RecentsContent(
    uiState: RecentsUiState,
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenDetails: (String) -> Unit = {},
) {
    when {
        uiState.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        !uiState.hasPermission -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val haptics = rememberHaptics()
            Text(
                text = stringResource(R.string.recents_no_permission),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = {
                    haptics.click()
                    onRequestPermissions()
                },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text(stringResource(R.string.common_grant_access))
            }
        }
        uiState.entries.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.recents_empty), style = MaterialTheme.typography.bodyLarge)
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(uiState.entries, key = { it.id }) { entry ->
                RecentsRow(entry = entry, onCall = onCall, onOpenDetails = onOpenDetails)
            }
        }
    }
}

@Composable
private fun RecentsRow(
    entry: CallLogEntry,
    onCall: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
) {
    val haptics = rememberHaptics()
    val typeLabel = stringResource(callTypeLabelRes(entry.type))
    val sourceSuffix = if (entry.source == CallSource.WHATSAPP) {
        " · " + stringResource(R.string.call_source_whatsapp)
    } else {
        ""
    }
    ListItem(
        modifier = clickableListItemModifier { onOpenDetails(entry.number) },
        headlineContent = { Text(entry.displayName ?: entry.number) },
        supportingContent = {
            Text(
                text = typeLabel + " · " +
                    DateUtils.getRelativeTimeSpanString(entry.timestampMillis) + sourceSuffix,
                color = if (entry.type == CallType.MISSED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        leadingContent = {
            val icon = when (entry.type) {
                CallType.OUTGOING -> Icons.AutoMirrored.Filled.CallMade
                CallType.BLOCKED -> Icons.Filled.Block
                CallType.MISSED, CallType.REJECTED -> Icons.AutoMirrored.Filled.CallMissed
                else -> Icons.AutoMirrored.Filled.CallReceived
            }
            Icon(icon, contentDescription = typeLabel)
        },
        trailingContent = {
            IconButton(
                onClick = {
                    haptics.confirm()
                    onCall(entry.number)
                },
            ) {
                Icon(
                    Icons.Filled.Call,
                    contentDescription = stringResource(R.string.dialpad_call),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}
