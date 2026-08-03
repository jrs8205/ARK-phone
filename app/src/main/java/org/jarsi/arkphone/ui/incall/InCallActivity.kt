package org.jarsi.arkphone.ui.incall

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallNotifications
import org.jarsi.arkphone.telecom.CallStatus
import org.jarsi.arkphone.ui.contactcard.ContactCardActivity
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        answerFromIntent(intent)
        setContent {
            ArkPhoneTheme {
                val viewModel: InCallViewModel = hiltViewModel()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val call = uiState.call
                var errorDismissed by rememberSaveable(call?.id) { mutableStateOf(false) }
                val disconnectError = call?.disconnectError
                    ?.takeIf { call.status == CallStatus.DISCONNECTED && !errorDismissed }
                // After a real conversation the whole app closes and leaves
                // recents (user request). A ring that was missed or declined
                // only closes this screen, so an unanswered call can never
                // pull the app out from under the user.
                val close = {
                    if (uiState.sawOffHook) finishAndRemoveTask() else finish()
                }
                InCallFinishGuard(
                    // An error explanation stays up until the user dismisses
                    // it — without this the guard would close the screen (or
                    // the whole app) before the text can be read.
                    hasCall = (call != null && call.status != CallStatus.DISCONNECTED) ||
                        disconnectError != null,
                    onFinish = close,
                )
                CallScreen(
                    uiState = uiState,
                    actions = viewModel,
                    onOpenContactCard = { contactId ->
                        startActivity(ContactCardActivity.intent(this, contactId))
                    },
                )
                disconnectError?.let { error ->
                    DisconnectErrorDialog(
                        error = error,
                        onDismiss = {
                            errorDismissed = true
                            close()
                        },
                    )
                }
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
