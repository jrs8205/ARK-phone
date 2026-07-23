package org.jarsi.arkphone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.telecom.DefaultDialerManager
import org.jarsi.arkphone.telecom.PhoneCaller
import org.jarsi.arkphone.ui.dialpad.DialpadScreen
import org.jarsi.arkphone.ui.navigation.MainScreen
import org.jarsi.arkphone.ui.onboarding.OnboardingScreen
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var phoneCaller: PhoneCaller
    @Inject lateinit var defaultDialerManager: DefaultDialerManager

    private val dialRequest = mutableStateOf<String?>(null)
    private val isDefault = mutableStateOf(false)
    private val hasPermissions = mutableStateOf(false)
    private val onboardingDismissed = mutableStateOf(false)

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
        setContent {
            ArkPhoneTheme {
                var dialpadOpen by rememberSaveable { mutableStateOf(false) }
                val requestedNumber by dialRequest
                LaunchedEffect(requestedNumber) {
                    if (requestedNumber != null) dialpadOpen = true
                }
                val defaultDialer by isDefault
                val permissionsGranted by hasPermissions
                val dismissed by onboardingDismissed
                val setupComplete = defaultDialer && permissionsGranted

                if (!setupComplete && !dismissed) {
                    OnboardingScreen(
                        onRequestRole = { roleLauncher.launch(defaultDialerManager.requestIntent()) },
                        onRequestPermissions = { permissionLauncher.launch(defaultDialerManager.corePermissions()) },
                        onDone = { onboardingDismissed.value = true },
                        isDefaultDialer = defaultDialer,
                        hasPermissions = permissionsGranted,
                    )
                } else if (dialpadOpen) {
                    DialpadScreen(
                        onCall = { number -> phoneCaller.placeCall(number) },
                        onClose = {
                            dialpadOpen = false
                            dialRequest.value = null
                        },
                        initialNumber = requestedNumber,
                    )
                } else {
                    MainScreen(
                        onCall = { number -> phoneCaller.placeCall(number) },
                        onOpenDialpad = { dialpadOpen = true },
                        onRequestPermissions = { permissionLauncher.launch(defaultDialerManager.corePermissions()) },
                        showDefaultDialerBanner = !defaultDialer,
                        onRequestDefaultDialer = { roleLauncher.launch(defaultDialerManager.requestIntent()) },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetupState()
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
