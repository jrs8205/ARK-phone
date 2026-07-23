package org.jarsi.arkphone.ui.incall

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallNotifications
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class InCallActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }

    @Inject lateinit var callController: CallController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        answerFromIntent(intent)
        setContent {
            ArkPhoneTheme {
                val viewModel: InCallViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                InCallFinishGuard(hasCall = uiState.call != null, onFinish = ::finish)
                CallScreen(uiState = uiState, actions = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        answerFromIntent(intent)
    }

    private fun answerFromIntent(intent: Intent?) {
        if (intent?.action == CallNotifications.ACTION_ANSWER) {
            intent.getStringExtra(CallNotifications.EXTRA_CALL_ID)?.let(callController::answer)
        }
    }
}
