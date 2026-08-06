package org.jarsi.arkphone.voip.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jarsi.arkphone.R
import org.jarsi.arkphone.voip.SignalingConnectionState
import org.jarsi.arkphone.voip.VoipCallState

@Composable
fun VoipTestScreen(viewModel: VoipTestViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.deviceId == null) {
            Text(stringResource(R.string.voip_pick_device), style = MaterialTheme.typography.titleLarge)
            Button(onClick = { viewModel.pickDevice("phone-8a") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.voip_device_8a))
            }
            Button(onClick = { viewModel.pickDevice("phone-10pro") }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.voip_device_10pro))
            }
            return@Column
        }

        Text(
            text = stringResource(R.string.voip_this_device, state.deviceId.orEmpty()),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = when (state.connectionState) {
                SignalingConnectionState.CONNECTED ->
                    if (state.peerOnline) stringResource(R.string.voip_peer_online)
                    else stringResource(R.string.voip_peer_offline)
                SignalingConnectionState.CONNECTING -> stringResource(R.string.voip_connecting)
                SignalingConnectionState.DISCONNECTED -> stringResource(R.string.voip_disconnected)
            },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.voip_relay_only))
            Switch(
                checked = state.relayOnly,
                onCheckedChange = viewModel::setRelayOnly,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        when (val call = state.callState) {
            VoipCallState.Idle -> Button(
                onClick = viewModel::placeCall,
                enabled = state.peerOnline,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.voip_call)) }

            VoipCallState.Connecting -> Text(stringResource(R.string.voip_call_connecting))

            is VoipCallState.Ringing -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::answer) { Text(stringResource(R.string.voip_answer)) }
                OutlinedButton(onClick = viewModel::reject) { Text(stringResource(R.string.voip_reject)) }
            }

            VoipCallState.InCall -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.voip_in_call))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::toggleSpeaker) {
                        Text(
                            if (state.speakerOn) stringResource(R.string.voip_speaker_off)
                            else stringResource(R.string.voip_speaker_on),
                        )
                    }
                    Button(onClick = viewModel::hangUp) { Text(stringResource(R.string.voip_hang_up)) }
                }
            }

            is VoipCallState.Ended -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.voip_call_ended, call.reason))
                Button(onClick = viewModel::dismissEnded) { Text(stringResource(R.string.voip_ok)) }
            }
        }

        state.stats?.let { stats ->
            Text(
                text = stringResource(
                    R.string.voip_stats,
                    if (stats.usingRelay) stringResource(R.string.voip_path_relay)
                    else stringResource(R.string.voip_path_direct),
                    stats.rttMs ?: -1,
                    stats.packetLossPercent ?: -1,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
