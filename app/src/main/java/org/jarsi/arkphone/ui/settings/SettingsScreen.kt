package org.jarsi.arkphone.ui.settings

import android.content.Intent
import android.telecom.TelecomManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.ui.components.rememberHaptics
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSimInfo: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SettingsContent(
        settings = settings,
        onAnnounceModeChanged = viewModel::onAnnounceModeChanged,
        onAnnounceIntervalChanged = viewModel::onAnnounceIntervalChanged,
        onOpenSimInfo = onOpenSimInfo,
        onOpenCallSettings = {
            runCatching {
                context.startActivity(Intent(TelecomManager.ACTION_SHOW_CALL_SETTINGS))
            }
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: Settings,
    onAnnounceModeChanged: (AnnounceMode) -> Unit,
    onAnnounceIntervalChanged: (Int) -> Unit,
    onOpenSimInfo: () -> Unit,
    onOpenCallSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val haptics = rememberHaptics()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.settings_announce_caller_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            AnnounceModeRow(
                title = stringResource(R.string.announce_mode_off),
                description = null,
                selected = settings.announceMode == AnnounceMode.OFF,
                onSelect = {
                    haptics.click()
                    onAnnounceModeChanged(AnnounceMode.OFF)
                },
            )
            AnnounceModeRow(
                title = stringResource(R.string.announce_mode_with_ringtone),
                description = stringResource(R.string.announce_mode_with_ringtone_description),
                selected = settings.announceMode == AnnounceMode.WITH_RINGTONE,
                onSelect = {
                    haptics.click()
                    onAnnounceModeChanged(AnnounceMode.WITH_RINGTONE)
                },
            )
            AnnounceModeRow(
                title = stringResource(R.string.announce_mode_voice_only),
                description = stringResource(R.string.announce_mode_voice_only_description),
                selected = settings.announceMode == AnnounceMode.VOICE_ONLY,
                onSelect = {
                    haptics.click()
                    onAnnounceModeChanged(AnnounceMode.VOICE_ONLY)
                },
            )
            if (settings.announceMode == AnnounceMode.VOICE_ONLY) {
                IntervalSlider(
                    intervalSeconds = settings.announceIntervalSeconds,
                    onIntervalChanged = onAnnounceIntervalChanged,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsLinkRow(
                title = stringResource(R.string.sim_cards_title),
                description = stringResource(R.string.sim_cards_description),
                onClick = {
                    haptics.click()
                    onOpenSimInfo()
                },
            )
            SettingsLinkRow(
                title = stringResource(R.string.settings_call_settings_title),
                description = stringResource(R.string.settings_call_settings_description),
                onClick = {
                    haptics.click()
                    onOpenCallSettings()
                },
            )
        }
    }
}

@Composable
private fun SettingsLinkRow(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@Composable
private fun AnnounceModeRow(
    title: String,
    description: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        // The selectable row is the single accessible target; a clickable
        // RadioButton inside it would double-report to accessibility services.
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun IntervalSlider(
    intervalSeconds: Int,
    onIntervalChanged: (Int) -> Unit,
) {
    var sliderValue by remember(intervalSeconds) { mutableFloatStateOf(intervalSeconds.toFloat()) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.announce_interval_label, sliderValue.roundToInt()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onIntervalChanged(sliderValue.roundToInt()) },
            valueRange = Settings.MIN_ANNOUNCE_INTERVAL_SECONDS.toFloat()..
                Settings.MAX_ANNOUNCE_INTERVAL_SECONDS.toFloat(),
            steps = Settings.MAX_ANNOUNCE_INTERVAL_SECONDS - Settings.MIN_ANNOUNCE_INTERVAL_SECONDS - 1,
        )
    }
}
