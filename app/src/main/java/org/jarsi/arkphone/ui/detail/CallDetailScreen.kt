package org.jarsi.arkphone.ui.detail

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.rememberHaptics
import org.jarsi.arkphone.ui.recents.callTypeLabelRes
import org.jarsi.arkphone.util.formatDuration

@Composable
fun CallDetailScreen(
    viewModel: CallDetailViewModel,
    onBack: () -> Unit,
    onCall: (String) -> Unit,
    onMessage: (String) -> Unit,
    onEditBeforeCall: (String) -> Unit,
    onDeleteHistory: () -> Unit,
    onWhatsAppCall: (String, String?) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    CallDetailContent(
        uiState = uiState,
        onBack = onBack,
        onCall = { onCall(uiState.number) },
        onMessage = { onMessage(uiState.number) },
        onCopyNumber = { clipboard.setText(AnnotatedString(uiState.number)) },
        onEditBeforeCall = { onEditBeforeCall(uiState.number) },
        onToggleBlocked = viewModel::onToggleBlocked,
        onDeleteHistory = onDeleteHistory,
        onWhatsAppCall = { onWhatsAppCall(uiState.number, uiState.displayName) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailContent(
    uiState: CallDetailUiState,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onMessage: () -> Unit,
    onCopyNumber: () -> Unit,
    onEditBeforeCall: () -> Unit,
    onToggleBlocked: () -> Unit,
    onDeleteHistory: () -> Unit,
    onWhatsAppCall: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = {
                            haptics.click()
                            onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.click()
                            menuOpen = true
                        },
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.detail_more_options),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.detail_copy_number)) },
                            onClick = {
                                haptics.click()
                                menuOpen = false
                                onCopyNumber()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.detail_edit_before_call)) },
                            onClick = {
                                haptics.click()
                                menuOpen = false
                                onEditBeforeCall()
                            },
                        )
                        if (uiState.canBlock) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (uiState.blocked) {
                                                R.string.detail_unblock
                                            } else {
                                                R.string.detail_block
                                            },
                                        ),
                                    )
                                },
                                onClick = {
                                    haptics.click()
                                    menuOpen = false
                                    onToggleBlocked()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.detail_delete_history)) },
                            onClick = {
                                haptics.click()
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ContactAvatar(
                displayName = uiState.displayName,
                photoUri = uiState.photoUri,
                size = 96.dp,
                initialTextStyle = MaterialTheme.typography.displaySmall,
            )
            Text(
                text = uiState.displayName ?: uiState.number,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
            if (uiState.displayName != null) {
                Text(
                    text = uiState.number,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uiState.blocked) {
                Text(
                    text = stringResource(R.string.detail_blocked_badge),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(vertical = 16.dp),
            ) {
                Button(
                    onClick = {
                        haptics.confirm()
                        onCall()
                    },
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null)
                    Text(
                        stringResource(R.string.dialpad_call),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                OutlinedButton(
                    onClick = {
                        haptics.click()
                        onMessage()
                    },
                ) {
                    Icon(Icons.Outlined.Message, contentDescription = null)
                    Text(
                        stringResource(R.string.detail_message),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (uiState.hasWhatsAppCalls) {
                    IconButton(
                        onClick = {
                            haptics.confirm()
                            onWhatsAppCall()
                        },
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_whatsapp),
                            contentDescription = stringResource(R.string.call_whatsapp),
                            tint = Color.Unspecified,
                        )
                    }
                }
            }
            StatsCard(uiState.stats)
            Text(
                text = stringResource(R.string.detail_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 4.dp),
            )
            uiState.entries.forEach { entry -> HistoryRow(entry) }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_delete_confirm_title)) },
            text = { Text(stringResource(R.string.detail_delete_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptics.click()
                        confirmDelete = false
                        onDeleteHistory()
                    },
                ) {
                    Text(stringResource(R.string.common_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatsCard(stats: CallStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.detail_stats_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            StatRow(stringResource(R.string.detail_stat_calls), stats.total.toString())
            StatRow(stringResource(R.string.detail_stat_outgoing), stats.outgoing.toString())
            StatRow(stringResource(R.string.detail_stat_incoming), stats.incoming.toString())
            StatRow(stringResource(R.string.detail_stat_missed), stats.missed.toString())
            StatRow(
                stringResource(R.string.detail_stat_talk_time),
                formatTalkTime(stats.totalDurationSeconds),
            )
            stats.latestMillis?.let { latest ->
                StatRow(
                    stringResource(R.string.detail_stat_latest),
                    DateUtils.getRelativeTimeSpanString(latest).toString(),
                )
            }
        }
    }
}

@Composable
private fun formatTalkTime(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> stringResource(R.string.duration_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.duration_minutes_seconds, minutes, seconds)
        else -> stringResource(R.string.duration_seconds, seconds)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HistoryRow(entry: CallLogEntry) {
    val typeLabel = stringResource(callTypeLabelRes(entry.type))
    val whatsapp = entry.source == CallSource.WHATSAPP
    ListItem(
        headlineContent = {
            Text(
                if (whatsapp) {
                    typeLabel + " · " + stringResource(R.string.call_source_whatsapp)
                } else {
                    typeLabel
                },
            )
        },
        supportingContent = {
            val time = DateUtils.formatDateTime(
                androidx.compose.ui.platform.LocalContext.current,
                entry.timestampMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
            )
            val duration = entry.durationSeconds
                .takeIf { it > 0 }
                ?.let { " · " + formatDuration(it) }
                .orEmpty()
            Text(time + duration)
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
    )
}
