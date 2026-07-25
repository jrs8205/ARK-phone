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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.ui.components.clickableListItemModifier
import org.jarsi.arkphone.ui.components.rememberHaptics

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
        onBlockHiddenNumbersChanged = viewModel::onBlockHiddenNumbersChanged,
        onBlockUnknownCallersChanged = viewModel::onBlockUnknownCallersChanged,
        onAllowRepeatCallersChanged = viewModel::onAllowRepeatCallersChanged,
        onAddBlockedPrefix = viewModel::onAddBlockedPrefix,
        onRemoveBlockedPrefix = viewModel::onRemoveBlockedPrefix,
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
    onBlockHiddenNumbersChanged: (Boolean) -> Unit = {},
    onBlockUnknownCallersChanged: (Boolean) -> Unit = {},
    onAllowRepeatCallersChanged: (Boolean) -> Unit = {},
    onAddBlockedPrefix: (String) -> Unit = {},
    onRemoveBlockedPrefix: (String) -> Unit = {},
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

@Composable
private fun BlockingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    ListItem(
        modifier = clickableListItemModifier { onCheckedChange(!checked) },
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
