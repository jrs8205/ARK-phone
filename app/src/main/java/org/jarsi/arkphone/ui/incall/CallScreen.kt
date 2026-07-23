package org.jarsi.arkphone.ui.incall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.jarsi.arkphone.R
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.ui.components.rememberHaptics
import org.jarsi.arkphone.ui.dialpad.DialpadGrid
import org.jarsi.arkphone.ui.theme.CallAnswerGreen
import org.jarsi.arkphone.ui.theme.CallDeclineRed

@Composable
fun CallScreen(uiState: InCallUiState, actions: InCallActions) {
    val call = uiState.call
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
                Text(
                    text = call?.displayName ?: call?.number
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
            modifier = Modifier.padding(top = 24.dp, bottom = 72.dp).size(72.dp),
        ) {
            Icon(
                Icons.Filled.CallEnd,
                contentDescription = stringResource(R.string.incall_end_call),
            )
        }
    }
}
