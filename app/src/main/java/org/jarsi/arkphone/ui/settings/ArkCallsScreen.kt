package org.jarsi.arkphone.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.ui.components.rememberHaptics

@Composable
fun ArkCallsScreen(
    onBack: () -> Unit,
    viewModel: ArkCallsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Resolved during composition so the text tracks configuration changes;
    // Context.getString inside the click lambda trips LocalContextGetResourceValueCall.
    val shareText = uiState.code?.let { stringResource(R.string.ark_calls_share_text, it) }
    ArkCallsContent(
        uiState = uiState,
        onBack = onBack,
        onNicknameChanged = viewModel::onNicknameChanged,
        onRegister = viewModel::onRegister,
        onEnabledChanged = viewModel::onEnabledChanged,
        onShare = {
            if (shareText != null) {
                val share = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, shareText)
                runCatching { context.startActivity(Intent.createChooser(share, null)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArkCallsContent(
    uiState: ArkCallsUiState,
    onBack: () -> Unit,
    onNicknameChanged: (String) -> Unit = {},
    onRegister: () -> Unit = {},
    onEnabledChanged: (Boolean) -> Unit = {},
    onShare: (String) -> Unit = {},
) {
    val haptics = rememberHaptics()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_ark_calls_title)) },
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
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (!uiState.available) {
                Text(
                    text = stringResource(R.string.ark_calls_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = uiState.enabled,
                        role = Role.Switch,
                        onValueChange = {
                            haptics.click()
                            onEnabledChanged(it)
                        },
                    )
                    .padding(vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        stringResource(R.string.ark_calls_switch),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.ark_calls_switch_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The toggleable row is the single accessible target.
                Switch(checked = uiState.enabled, onCheckedChange = null)
            }
            Text(
                stringResource(R.string.ark_calls_your_code),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            val code = uiState.code
            if (code == null) {
                OutlinedTextField(
                    value = uiState.nickname,
                    onValueChange = onNicknameChanged,
                    singleLine = true,
                    label = { Text(stringResource(R.string.ark_calls_nickname)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (uiState.registerFailed) {
                    Text(
                        stringResource(R.string.ark_calls_register_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Button(
                    onClick = onRegister,
                    enabled = !uiState.registering,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(
                        stringResource(
                            if (uiState.registering) {
                                R.string.ark_calls_registering
                            } else {
                                R.string.ark_calls_register
                            },
                        ),
                    )
                }
            } else {
                Text(code, style = MaterialTheme.typography.headlineSmall)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    OutlinedButton(onClick = { onShare(code) }) {
                        Text(stringResource(R.string.ark_calls_share))
                    }
                }
            }
        }
    }
}
