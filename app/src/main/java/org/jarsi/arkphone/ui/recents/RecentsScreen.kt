package org.jarsi.arkphone.ui.recents

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.ui.components.RowCard
import org.jarsi.arkphone.ui.components.clickableListItemModifier
import org.jarsi.arkphone.ui.components.rememberHaptics
import org.jarsi.arkphone.ui.components.transparentListItemColors

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
    Column(Modifier.fillMaxSize()) {
        if (uiState.hasPermission) {
            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.recents_search_hint)) },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                RecentsFilterChip(uiState.filter, RecentsFilter.ALL, R.string.recents_filter_all, viewModel::onFilterChange)
                RecentsFilterChip(uiState.filter, RecentsFilter.MISSED, R.string.recents_filter_missed, viewModel::onFilterChange)
                RecentsFilterChip(uiState.filter, RecentsFilter.WHATSAPP, R.string.call_source_whatsapp, viewModel::onFilterChange)
            }
        }
        Box(Modifier.weight(1f)) {
            RecentsContent(
                uiState = uiState,
                onCall = onCall,
                onRequestPermissions = onRequestPermissions,
                onOpenDetails = onOpenDetails,
                onWhatsAppCall = viewModel::onWhatsAppCall,
            )
        }
    }
}

@Composable
private fun RecentsFilterChip(
    selected: RecentsFilter,
    filter: RecentsFilter,
    @StringRes labelRes: Int,
    onSelect: (RecentsFilter) -> Unit,
) {
    val haptics = rememberHaptics()
    FilterChip(
        selected = selected == filter,
        onClick = {
            haptics.click()
            onSelect(filter)
        },
        label = { Text(stringResource(labelRes)) },
    )
}

@Composable
fun RecentsContent(
    uiState: RecentsUiState,
    onCall: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenDetails: (String) -> Unit = {},
    onWhatsAppCall: (CallLogEntry) -> Unit = {},
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
            items(uiState.entries, key = { it.entry.id }) { grouped ->
                RecentsRow(
                    grouped = grouped,
                    onCall = onCall,
                    onOpenDetails = onOpenDetails,
                    onWhatsAppCall = onWhatsAppCall,
                )
            }
        }
    }
}

@Composable
private fun RecentsRow(
    grouped: GroupedCallLogEntry,
    onCall: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onWhatsAppCall: (CallLogEntry) -> Unit,
) {
    RowCard(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        RecentsRowItem(grouped, onCall, onOpenDetails, onWhatsAppCall)
    }
}

@Composable
private fun RecentsRowItem(
    grouped: GroupedCallLogEntry,
    onCall: (String) -> Unit,
    onOpenDetails: (String) -> Unit,
    onWhatsAppCall: (CallLogEntry) -> Unit,
) {
    val entry = grouped.entry
    val haptics = rememberHaptics()
    val typeLabel = stringResource(callTypeLabelRes(entry.type))
    val sourceSuffix = if (entry.source == CallSource.WHATSAPP) {
        " · " + stringResource(R.string.call_source_whatsapp)
    } else {
        ""
    }
    ListItem(
        colors = transparentListItemColors(),
        modifier = clickableListItemModifier {
            if (entry.number.isNotBlank()) onOpenDetails(entry.number)
        },
        headlineContent = {
            val name = entry.displayName
                ?: entry.number.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.call_log_unknown_caller)
            Text(if (grouped.count > 1) "$name (${grouped.count})" else name)
        },
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
            if (entry.source == CallSource.WHATSAPP) {
                IconButton(
                    onClick = {
                        haptics.confirm()
                        onWhatsAppCall(entry)
                    },
                ) {
                    Icon(
                        painterResource(R.drawable.ic_whatsapp),
                        contentDescription = stringResource(R.string.call_whatsapp),
                        tint = Color.Unspecified,
                    )
                }
            } else {
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
            }
        },
    )
}
