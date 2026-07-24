package org.jarsi.arkphone.ui.settings

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.SimCard
import org.jarsi.arkphone.ui.components.rememberHaptics

@Composable
fun SimInfoScreen(
    onBack: () -> Unit,
    viewModel: SimInfoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refresh() }
    SimInfoContent(
        uiState = uiState,
        onBack = onBack,
        onRequestNumberPermission = {
            permissionLauncher.launch(Manifest.permission.READ_PHONE_NUMBERS)
        },
        onOpenSystemSettings = {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimInfoContent(
    uiState: SimInfoUiState,
    onBack: () -> Unit,
    onRequestNumberPermission: () -> Unit,
    onOpenSystemSettings: () -> Unit,
) {
    val haptics = rememberHaptics()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sim_cards_title)) },
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
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (!uiState.loading && uiState.sims.isEmpty()) {
                Text(
                    text = stringResource(R.string.sim_empty),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            uiState.sims.forEach { sim ->
                SimCardItem(sim)
                Spacer(Modifier.height(12.dp))
            }
            if (uiState.showNumberPermissionButton) {
                TextButton(
                    onClick = {
                        haptics.click()
                        onRequestNumberPermission()
                    },
                ) {
                    Text(stringResource(R.string.sim_show_numbers))
                }
            }
            OutlinedButton(
                onClick = {
                    haptics.click()
                    onOpenSystemSettings()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.sim_open_system_settings))
            }
        }
    }
}

@Composable
private fun SimCardItem(sim: SimCard) {
    val slotLabel = if (sim.isEmbedded) {
        "eSIM"
    } else {
        stringResource(R.string.sim_slot_physical, sim.slotIndex + 1)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = sim.displayName ?: sim.carrierName ?: slotLabel,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            SimInfoRow(stringResource(R.string.sim_operator), sim.carrierName)
            SimInfoRow(stringResource(R.string.sim_number), sim.number)
            SimInfoRow(stringResource(R.string.sim_slot), slotLabel)
            SimInfoRow(stringResource(R.string.sim_country), sim.countryIso)
        }
    }
}

@Composable
private fun SimInfoRow(label: String, value: String?) {
    if (value == null) return
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
