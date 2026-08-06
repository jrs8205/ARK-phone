package org.jarsi.arkphone.voip.ui

import android.Manifest
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.VoipForegroundService

@AndroidEntryPoint
class VoipTestActivity : ComponentActivity(), VoipAudioController {

    private val viewModel: VoipTestViewModel by viewModels()

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.audioController = this
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
        setContent {
            MaterialTheme {
                VoipTestScreen(viewModel)
            }
        }
    }

    override fun onDestroy() {
        viewModel.audioController = null
        super.onDestroy()
    }

    override fun onCallStateChanged(state: VoipCallState) {
        when (state) {
            is VoipCallState.Connecting, is VoipCallState.InCall ->
                VoipForegroundService.start(this)
            is VoipCallState.Ended, VoipCallState.Idle ->
                VoipForegroundService.stop(this)
            else -> Unit
        }
    }

    @Suppress("DEPRECATION")
    override fun setSpeaker(on: Boolean) {
        getSystemService(AudioManager::class.java).isSpeakerphoneOn = on
    }
}
