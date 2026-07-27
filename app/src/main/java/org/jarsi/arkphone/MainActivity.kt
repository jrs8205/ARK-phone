package org.jarsi.arkphone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.jarsi.arkphone.telecom.BlockedCallNotifier
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.DefaultDialerManager
import org.jarsi.arkphone.telecom.MissedCallNotifier
import org.jarsi.arkphone.telecom.PhoneCaller
import org.jarsi.arkphone.ui.navigation.MainScreen
import org.jarsi.arkphone.ui.onboarding.OnboardingScreen
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var phoneCaller: PhoneCaller
    @Inject lateinit var defaultDialerManager: DefaultDialerManager
    @Inject lateinit var missedCallNotifier: MissedCallNotifier
    @Inject lateinit var blockedCallNotifier: BlockedCallNotifier
    @Inject lateinit var callController: CallController

    private companion object {
        // A touch longer than the call screen's ended-grace, so the visible
        // "call ended" moment is never cut short from below.
        const val APP_CLOSE_GRACE_MILLIS = 2_000L
    }

    private val dialRequest = mutableStateOf<String?>(null)
    private val isDefault = mutableStateOf(false)
    private val hasPermissions = mutableStateOf(false)

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshSetupState() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshSetupState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readDialIntent(intent)
        refreshSetupState()
        // The user wants the whole app gone once a call ends. The call screen
        // handles this itself when visible; this covers the case where it is
        // buried under other app screens and its finish guard is paused.
        lifecycleScope.launch {
            callController.allCallsEnded.collectLatest {
                delay(APP_CLOSE_GRACE_MILLIS)
                if (callController.calls.value.isEmpty()) finishAndRemoveTask()
            }
        }
        setContent {
            ArkPhoneTheme {
                var onboardingDismissed by rememberSaveable { mutableStateOf(false) }
                val requestedNumber by dialRequest
                val defaultDialer by isDefault
                val permissionsGranted by hasPermissions
                val setupComplete = defaultDialer && permissionsGranted

                if (!setupComplete && !onboardingDismissed) {
                    OnboardingScreen(
                        onRequestRole = { roleLauncher.launch(defaultDialerManager.requestIntent()) },
                        onRequestPermissions = { permissionLauncher.launch(defaultDialerManager.requestablePermissions()) },
                        onDone = { onboardingDismissed = true },
                        isDefaultDialer = defaultDialer,
                        hasPermissions = permissionsGranted,
                    )
                } else {
                    MainScreen(
                        onCall = { number -> phoneCaller.placeCall(number) },
                        onVoicemail = { phoneCaller.placeVoicemailCall() },
                        onRequestPermissions = { permissionLauncher.launch(defaultDialerManager.requestablePermissions()) },
                        showDefaultDialerBanner = !defaultDialer,
                        onRequestDefaultDialer = { roleLauncher.launch(defaultDialerManager.requestIntent()) },
                        requestedNumber = requestedNumber,
                        onRequestedNumberConsumed = { dialRequest.value = null },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupState()
        missedCallNotifier.onCallLogSeen()
        blockedCallNotifier.onSeen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDialIntent(intent)
    }

    private fun readDialIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_DIAL || intent?.action == Intent.ACTION_VIEW) {
            dialRequest.value = intent.data?.schemeSpecificPart ?: ""
        }
    }

    private fun refreshSetupState() {
        isDefault.value = defaultDialerManager.isDefaultDialer()
        hasPermissions.value = defaultDialerManager.hasCorePermissions()
    }
}
