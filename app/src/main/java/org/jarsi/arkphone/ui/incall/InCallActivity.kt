package org.jarsi.arkphone.ui.incall

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme

@AndroidEntryPoint
class InCallActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            ArkPhoneTheme {
                val viewModel: InCallViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                var sawCall by remember { mutableStateOf(false) }
                LaunchedEffect(uiState.call) {
                    if (uiState.call != null) {
                        sawCall = true
                    } else if (sawCall) {
                        finish()
                    }
                }
                CallScreen(uiState = uiState, actions = viewModel)
            }
        }
    }
}
