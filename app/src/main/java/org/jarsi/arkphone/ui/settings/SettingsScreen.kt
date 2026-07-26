package org.jarsi.arkphone.ui.settings

import android.content.Intent
import android.speech.tts.TextToSpeech
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.data.model.SimAccount
import org.jarsi.arkphone.telecom.SpeechStatus
import org.jarsi.arkphone.ui.components.rememberHaptics
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenSimInfo: () -> Unit,
    onOpenBlocking: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.uiState.collectAsStateWithLifecycle()
    val hasNotificationAccess by viewModel.hasNotificationAccess.collectAsStateWithLifecycle()
    val simAccounts by viewModel.simAccounts.collectAsStateWithLifecycle()
    val speechStatus by viewModel.speechStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refreshNotificationAccess()
        viewModel.refreshSimAccounts()
        viewModel.refreshSpeechStatus()
        onPauseOrDispose { }
    }
    SettingsContent(
        settings = settings,
        hasNotificationAccess = hasNotificationAccess,
        simAccounts = simAccounts,
        onCallSimAccountChanged = viewModel::onCallSimAccountChanged,
        speechStatus = speechStatus,
        speechLanguage = LocalConfiguration.current.locales[0].let { it.getDisplayLanguage(it) },
        versionName = remember(context) {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull().orEmpty()
        },
        onOpenSpeechSettings = {
            // The voice installer is the engine's own screen and not every
            // engine offers it; the system speech settings always exist.
            val installed = runCatching {
                context.startActivity(
                    Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
            if (!installed) {
                runCatching {
                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                }
            }
        },
        onAnnounceModeChanged = viewModel::onAnnounceModeChanged,
        onAnnounceIntervalChanged = viewModel::onAnnounceIntervalChanged,
        onAnnounceWhatsAppChanged = viewModel::onAnnounceWhatsAppChanged,
        onGrantNotificationAccess = {
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                )
            }
        },
        onOpenSimInfo = onOpenSimInfo,
        onOpenBlocking = onOpenBlocking,
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
    onOpenBlocking: () -> Unit = {},
    hasNotificationAccess: Boolean = true,
    onAnnounceWhatsAppChanged: (Boolean) -> Unit = {},
    onGrantNotificationAccess: () -> Unit = {},
    simAccounts: List<SimAccount> = emptyList(),
    onCallSimAccountChanged: (String?) -> Unit = {},
    speechStatus: SpeechStatus = SpeechStatus.READY,
    onOpenSpeechSettings: () -> Unit = {},
    versionName: String = "",
    speechLanguage: String = "",
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
            if (speechStatus == SpeechStatus.UNAVAILABLE) {
                // Offering a spoken announcement on a phone that cannot speak
                // would be a setting that silently does nothing.
                SpeechNotice(
                    text = stringResource(R.string.settings_speech_unavailable),
                    button = stringResource(R.string.settings_speech_settings),
                    onClick = onOpenSpeechSettings,
                )
            } else {
            if (speechStatus == SpeechStatus.VOICE_MISSING) {
                SpeechNotice(
                    text = stringResource(R.string.settings_speech_voice_missing, speechLanguage),
                    button = stringResource(R.string.settings_speech_install),
                    onClick = onOpenSpeechSettings,
                )
            }
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
            if (settings.announceMode == AnnounceMode.VOICE_ONLY || settings.announceWhatsApp) {
                IntervalSlider(
                    intervalSeconds = settings.announceIntervalSeconds,
                    onIntervalChanged = onAnnounceIntervalChanged,
                )
            }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                text = stringResource(R.string.settings_whatsapp_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = settings.announceWhatsApp,
                        role = Role.Switch,
                        onValueChange = {
                            haptics.click()
                            onAnnounceWhatsAppChanged(it)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(
                        stringResource(R.string.settings_announce_whatsapp_title),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_announce_whatsapp_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The toggleable row is the single accessible target.
                Switch(checked = settings.announceWhatsApp, onCheckedChange = null)
            }
            if (settings.announceWhatsApp && !hasNotificationAccess) {
                Button(
                    onClick = {
                        haptics.click()
                        onGrantNotificationAccess()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_grant_notification_access))
                }
            }
            // One SIM means there is nothing to choose between.
            if (simAccounts.size > 1) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.settings_call_sim_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                AnnounceModeRow(
                    title = stringResource(R.string.settings_sim_system_default),
                    description = stringResource(R.string.settings_call_sim_description),
                    selected = settings.callSimAccountId == null,
                    onSelect = {
                        haptics.click()
                        onCallSimAccountChanged(null)
                    },
                )
                simAccounts.forEach { account ->
                    AnnounceModeRow(
                        title = account.labelWithNumber,
                        description = null,
                        selected = settings.callSimAccountId == account.id,
                        onSelect = {
                            haptics.click()
                            onCallSimAccountChanged(account.id)
                        },
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsLinkRow(
                title = stringResource(R.string.settings_blocking_title),
                description = stringResource(R.string.settings_blocking_description),
                onClick = {
                    haptics.click()
                    onOpenBlocking()
                },
            )
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
            Text(
                text = stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
    }
}

@Composable
private fun SpeechNotice(text: String, button: String, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                haptics.click()
                onClick()
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text(button)
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
            text = pluralStringResource(
                R.plurals.announce_interval_label,
                sliderValue.roundToInt(),
                sliderValue.roundToInt(),
            ),
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
