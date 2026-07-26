package org.jarsi.arkphone.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.ui.components.clickableListItem
import org.jarsi.arkphone.ui.components.rememberHaptics
import kotlin.math.roundToInt

@Composable
fun BlockingScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.uiState.collectAsStateWithLifecycle()
    val hasScreeningRole by viewModel.hasScreeningRole.collectAsStateWithLifecycle()
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshScreeningRole() }
    LifecycleResumeEffect(Unit) {
        viewModel.refreshScreeningRole()
        onPauseOrDispose { }
    }
    BlockingContent(
        settings = settings,
        hasScreeningRole = hasScreeningRole,
        onBack = onBack,
        onBlockAllCallersChanged = viewModel::onBlockAllCallersChanged,
        onBlockHiddenNumbersChanged = viewModel::onBlockHiddenNumbersChanged,
        onBlockUnknownCallersChanged = viewModel::onBlockUnknownCallersChanged,
        onAllowRepeatCallersChanged = viewModel::onAllowRepeatCallersChanged,
        onAddBlockedPrefix = viewModel::onAddBlockedPrefix,
        onRemoveBlockedPrefix = viewModel::onRemoveBlockedPrefix,
        onRepeatWindowChanged = viewModel::onRepeatCallerWindowChanged,
        onAlwaysAllowFavoritesChanged = viewModel::onAlwaysAllowFavoritesChanged,
        onAddAllowedNumber = viewModel::onAddAllowedNumber,
        onRemoveAllowedNumber = viewModel::onRemoveAllowedNumber,
        onScheduleEnabledChanged = viewModel::onBlockingScheduleEnabledChanged,
        onScheduleChanged = viewModel::onBlockingScheduleChanged,
        onRequestScreeningRole = {
            viewModel.screeningRoleRequestIntent()?.let { intent ->
                runCatching { roleLauncher.launch(intent) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingContent(
    settings: Settings,
    onBack: () -> Unit,
    hasScreeningRole: Boolean = true,
    onBlockAllCallersChanged: (Boolean) -> Unit = {},
    onBlockHiddenNumbersChanged: (Boolean) -> Unit = {},
    onBlockUnknownCallersChanged: (Boolean) -> Unit = {},
    onAllowRepeatCallersChanged: (Boolean) -> Unit = {},
    onRepeatWindowChanged: (Int) -> Unit = {},
    onAddBlockedPrefix: (String) -> Unit = {},
    onRemoveBlockedPrefix: (String) -> Unit = {},
    onAlwaysAllowFavoritesChanged: (Boolean) -> Unit = {},
    onAddAllowedNumber: (String) -> Unit = {},
    onRemoveAllowedNumber: (String) -> Unit = {},
    onScheduleEnabledChanged: (Boolean) -> Unit = {},
    onScheduleChanged: (Int, Int) -> Unit = { _, _ -> },
    onRequestScreeningRole: () -> Unit = {},
) {
    val haptics = rememberHaptics()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_blocking_title)) },
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
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            if (!hasScreeningRole) {
                Card(Modifier.padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.blocking_role_description),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Button(
                            onClick = {
                                haptics.click()
                                onRequestScreeningRole()
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) {
                            Text(stringResource(R.string.blocking_role_button))
                        }
                    }
                }
            }
            BlockingSwitchRow(
                title = stringResource(R.string.blocking_all_title),
                description = stringResource(R.string.blocking_all_description),
                checked = settings.blockAllCallers,
                onCheckedChange = onBlockAllCallersChanged,
            )
            BlockingSwitchRow(
                title = stringResource(R.string.blocking_hidden_title),
                description = stringResource(R.string.blocking_hidden_description),
                checked = settings.blockHiddenNumbers,
                onCheckedChange = onBlockHiddenNumbersChanged,
            )
            BlockingSwitchRow(
                title = stringResource(R.string.blocking_unknown_title),
                description = stringResource(R.string.blocking_unknown_description),
                checked = settings.blockUnknownCallers,
                onCheckedChange = onBlockUnknownCallersChanged,
            )
            BlockingSwitchRow(
                title = stringResource(R.string.blocking_repeat_title),
                description = stringResource(R.string.blocking_repeat_description),
                checked = settings.allowRepeatCallers,
                onCheckedChange = onAllowRepeatCallersChanged,
            )
            if (settings.allowRepeatCallers) {
                Text(
                    text = stringResource(
                        R.string.blocking_repeat_window,
                        settings.repeatCallerWindowMinutes,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Slider(
                    value = settings.repeatCallerWindowMinutes.toFloat(),
                    onValueChange = { onRepeatWindowChanged(it.roundToInt()) },
                    valueRange = Settings.MIN_REPEAT_WINDOW_MINUTES.toFloat()..
                        Settings.MAX_REPEAT_WINDOW_MINUTES.toFloat(),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            BlockingSwitchRow(
                title = stringResource(R.string.blocking_favorites_title),
                description = stringResource(R.string.blocking_favorites_description),
                checked = settings.alwaysAllowFavorites,
                onCheckedChange = onAlwaysAllowFavoritesChanged,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            BlockingSwitchRow(
                title = stringResource(R.string.blocking_schedule_title),
                description = stringResource(R.string.blocking_schedule_description),
                checked = settings.blockingScheduleEnabled,
                onCheckedChange = onScheduleEnabledChanged,
            )
            if (settings.blockingScheduleEnabled) {
                var editStart by rememberSaveable { mutableStateOf(false) }
                var editEnd by rememberSaveable { mutableStateOf(false) }
                ListItem(
                    modifier = Modifier.clickableListItem { editStart = true },
                    headlineContent = { Text(stringResource(R.string.blocking_schedule_start)) },
                    trailingContent = { Text(formatScheduleTime(settings.blockingScheduleStartMinutes)) },
                )
                ListItem(
                    modifier = Modifier.clickableListItem { editEnd = true },
                    headlineContent = { Text(stringResource(R.string.blocking_schedule_end)) },
                    trailingContent = { Text(formatScheduleTime(settings.blockingScheduleEndMinutes)) },
                )
                if (editStart) {
                    BlockingTimePickerDialog(
                        initialMinutes = settings.blockingScheduleStartMinutes,
                        onConfirm = { minutes ->
                            editStart = false
                            onScheduleChanged(minutes, settings.blockingScheduleEndMinutes)
                        },
                        onDismiss = { editStart = false },
                    )
                }
                if (editEnd) {
                    BlockingTimePickerDialog(
                        initialMinutes = settings.blockingScheduleEndMinutes,
                        onConfirm = { minutes ->
                            editEnd = false
                            onScheduleChanged(settings.blockingScheduleStartMinutes, minutes)
                        },
                        onDismiss = { editEnd = false },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.blocking_allowed_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.blocking_allowed_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            settings.allowedNumbers.sorted().forEach { number ->
                ListItem(
                    headlineContent = { Text(number) },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                haptics.click()
                                onRemoveAllowedNumber(number)
                            },
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.blocking_prefix_remove),
                            )
                        }
                    },
                )
            }
            var newAllowed by rememberSaveable { mutableStateOf("") }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = newAllowed,
                    onValueChange = { newAllowed = it },
                    placeholder = { Text(stringResource(R.string.blocking_allowed_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        haptics.click()
                        onAddAllowedNumber(newAllowed)
                        newAllowed = ""
                    },
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(stringResource(R.string.blocking_prefix_add))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.blocking_prefixes_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Text(
                text = stringResource(R.string.blocking_prefixes_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            settings.blockedPrefixes.sorted().forEach { prefix ->
                ListItem(
                    headlineContent = { Text(prefix) },
                    trailingContent = {
                        IconButton(
                            onClick = {
                                haptics.click()
                                onRemoveBlockedPrefix(prefix)
                            },
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.blocking_prefix_remove),
                            )
                        }
                    },
                )
            }
            var newPrefix by rememberSaveable { mutableStateOf("") }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedTextField(
                    value = newPrefix,
                    onValueChange = { newPrefix = it },
                    placeholder = { Text(stringResource(R.string.blocking_prefix_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        haptics.click()
                        onAddBlockedPrefix(newPrefix)
                        newPrefix = ""
                    },
                    modifier = Modifier.padding(start = 12.dp),
                ) {
                    Text(stringResource(R.string.blocking_prefix_add))
                }
            }
            Text(
                text = stringResource(R.string.blocking_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Formats minutes-of-day honoring the device's 12/24-hour clock setting. */
@Composable
private fun formatScheduleTime(minutes: Int): String {
    val context = LocalContext.current
    val calendar = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, minutes / 60)
        set(java.util.Calendar.MINUTE, minutes % 60)
    }
    return DateFormat.getTimeFormat(context).format(calendar.time)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BlockingTimePickerDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun BlockingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    ListItem(
        modifier = Modifier.clickableListItem { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = {
                    haptics.click()
                    onCheckedChange(it)
                },
            )
        },
    )
}
