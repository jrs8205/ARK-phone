package org.jarsi.arkphone.ui.incall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jarsi.arkphone.R
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.ui.components.ContactAvatar
import org.jarsi.arkphone.ui.components.clickableListItem
import org.jarsi.arkphone.ui.components.rememberHaptics
import org.jarsi.arkphone.ui.dialpad.DialpadGrid
import org.jarsi.arkphone.ui.theme.CallAnswerGreen
import org.jarsi.arkphone.ui.theme.CallDeclineRed

@Composable
fun CallScreen(
    uiState: InCallUiState,
    actions: InCallActions,
    onOpenContactCard: (Long) -> Unit = {},
) {
    val call = uiState.call
    val haptics = rememberHaptics()
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp),
            ) {
                val contactId = uiState.callerContactId
                // Telecom only provides the contact name from API 30 on; the
                // contacts-lookup name backs it up for older devices.
                val callerName = call?.displayName ?: uiState.callerDisplayName
                ContactAvatar(
                    displayName = callerName,
                    photoUri = uiState.callerPhotoUri,
                    size = 112.dp,
                    initialTextStyle = MaterialTheme.typography.displaySmall,
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .testTag("callerAvatar")
                        .then(
                            if (contactId != null) {
                                Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        haptics.click()
                                        onOpenContactCard(contactId)
                                    }
                            } else {
                                Modifier
                            },
                        ),
                )
                Text(
                    text = callerName ?: call?.number
                        ?: stringResource(R.string.incall_unknown_caller),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = when (call?.status) {
                        CallStatus.RINGING -> stringResource(R.string.incall_state_ringing)
                        CallStatus.DIALING -> stringResource(R.string.incall_state_dialing)
                        CallStatus.HOLDING -> stringResource(R.string.incall_state_holding)
                        CallStatus.DISCONNECTED, CallStatus.DISCONNECTING ->
                            stringResource(R.string.incall_state_ended)
                        else -> uiState.elapsed.orEmpty()
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                uiState.simLabel?.let { sim ->
                    Text(
                        text = stringResource(R.string.incall_sim, sim),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (call?.viaArkCall == true) {
                    Text(
                        text = stringResource(R.string.incall_ark_call),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (call?.status == CallStatus.RINGING) {
                    if (!uiState.knownCaller && callerName == null) {
                        Text(
                            text = stringResource(R.string.incall_unknown_number),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    uiState.lastCalledMillis?.let { lastCalled ->
                        Text(
                            text = stringResource(
                                R.string.incall_last_called,
                                DateUtils.getRelativeTimeSpanString(lastCalled),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            if (uiState.showKeypad) {
                DialpadGrid(onKey = actions::onDtmf)
            }

            if (call?.status == CallStatus.RINGING) {
                IncomingCallActions(actions)
            } else {
                OngoingCallActions(uiState, actions)
            }
        }
    }
}

@Composable
private fun IncomingCallActions(actions: InCallActions) {
    val haptics = rememberHaptics()
    var showReplies by remember { mutableStateOf(false) }
    if (showReplies) {
        val replies = listOf(
            stringResource(R.string.incall_reject_reply_1),
            stringResource(R.string.incall_reject_reply_2),
            stringResource(R.string.incall_reject_reply_3),
        )
        AlertDialog(
            onDismissRequest = { showReplies = false },
            title = { Text(stringResource(R.string.incall_reject_message_title)) },
            text = {
                Column {
                    replies.forEach { reply ->
                        ListItem(
                            modifier = Modifier.clickableListItem {
                                showReplies = false
                                actions.onRejectWithMessage(reply)
                            },
                            headlineContent = { Text(reply) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showReplies = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(bottom = 24.dp),
        ) {
            FilledTonalButton(
                onClick = {
                    haptics.click()
                    actions.onSilence()
                },
            ) {
                Icon(Icons.Filled.NotificationsOff, contentDescription = null)
                Text(
                    stringResource(R.string.incall_silence),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            FilledTonalButton(
                onClick = {
                    haptics.click()
                    showReplies = true
                },
            ) {
                Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = null)
                Text(
                    stringResource(R.string.incall_reject_message),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        IncomingAnswerButtons(actions)
    }
}

@Composable
private fun IncomingAnswerButtons(actions: InCallActions) {
    val haptics = rememberHaptics()
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 72.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        FloatingActionButton(
            onClick = {
                haptics.reject()
                actions.onReject()
            },
            containerColor = CallDeclineRed,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                Icons.Filled.CallEnd,
                contentDescription = stringResource(R.string.incall_decline),
            )
        }
        FloatingActionButton(
            onClick = {
                haptics.confirm()
                actions.onAnswer()
            },
            containerColor = CallAnswerGreen,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(Icons.Filled.Call, contentDescription = stringResource(R.string.incall_answer))
        }
    }
}

@Composable
private fun OngoingCallActions(uiState: InCallUiState, actions: InCallActions) {
    val haptics = rememberHaptics()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledIconToggleButton(
                checked = uiState.muted,
                onCheckedChange = {
                    haptics.click()
                    actions.onToggleMute()
                },
            ) {
                Icon(
                    if (uiState.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = stringResource(R.string.incall_mute),
                )
            }
            FilledIconToggleButton(
                checked = uiState.speakerOn,
                onCheckedChange = {
                    haptics.click()
                    actions.onToggleSpeaker()
                },
            ) {
                Icon(
                    Icons.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.incall_speaker),
                )
            }
            FilledIconToggleButton(
                checked = uiState.call?.status == CallStatus.HOLDING,
                onCheckedChange = {
                    haptics.click()
                    actions.onToggleHold()
                },
            ) {
                Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.incall_hold))
            }
            FilledIconToggleButton(
                checked = uiState.showKeypad,
                onCheckedChange = {
                    haptics.click()
                    actions.onToggleKeypad()
                },
            ) {
                Icon(Icons.Filled.Dialpad, contentDescription = stringResource(R.string.incall_keypad))
            }
        }
        FloatingActionButton(
            onClick = {
                haptics.reject()
                actions.onHangUp()
            },
            containerColor = CallDeclineRed,
            contentColor = Color.White,
            shape = CircleShape,
            modifier = Modifier.padding(top = 24.dp, bottom = 72.dp).size(72.dp),
        ) {
            Icon(
                Icons.Filled.CallEnd,
                contentDescription = stringResource(R.string.incall_end_call),
            )
        }
    }
}
