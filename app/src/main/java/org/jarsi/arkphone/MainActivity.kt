package org.jarsi.arkphone

import android.Manifest
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.MessagesRepository
import org.jarsi.arkphone.messaging.SmsRole
import org.jarsi.arkphone.telecom.BlockedCallNotifier
import org.jarsi.arkphone.telecom.DefaultDialerManager
import org.jarsi.arkphone.telecom.MissedCallNotifier
import org.jarsi.arkphone.telecom.PhoneCaller
import org.jarsi.arkphone.ui.conversation.ConversationActivity
import org.jarsi.arkphone.ui.messages.NewMessageActivity
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
    @Inject lateinit var smsRole: SmsRole
    @Inject lateinit var messagesRepository: MessagesRepository

    private val dialRequest = mutableStateOf<String?>(null)
    private val isDefault = mutableStateOf(false)
    private val hasPermissions = mutableStateOf(false)
    private val unreadMessages = mutableStateOf(0)

    // The Messages tab refreshes its own role state on resume.
    private val smsRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { }

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshSetupState() }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshSetupState() }

    // The Messages tab asks for SMS access contextually; the tab's resume
    // refresh picks the grant up, so no callback state is needed here.
    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readDialIntent(intent)
        refreshSetupState()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                messagesRepository.conversations()
                    .map { conversations -> conversations.count { it.unread } }
                    .collect { unreadMessages.value = it }
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
                        onRequestSmsPermission = {
                            smsPermissionLauncher.launch(Manifest.permission.READ_SMS)
                        },
                        onOpenThread = { threadId ->
                            startActivity(ConversationActivity.intent(this, threadId))
                        },
                        onNewMessage = {
                            startActivity(NewMessageActivity.intent(this))
                        },
                        onRequestSmsRole = {
                            smsRole.requestIntent()?.let(smsRoleLauncher::launch)
                        },
                        unreadMessages = unreadMessages.value,
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
