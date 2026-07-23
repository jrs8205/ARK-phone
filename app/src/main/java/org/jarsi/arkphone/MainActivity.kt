package org.jarsi.arkphone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.telecom.PhoneCaller
import org.jarsi.arkphone.ui.dialpad.DialpadScreen
import org.jarsi.arkphone.ui.navigation.MainScreen
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var phoneCaller: PhoneCaller

    private val dialRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readDialIntent(intent)
        setContent {
            ArkPhoneTheme {
                var dialpadOpen by rememberSaveable { mutableStateOf(false) }
                val requestedNumber by dialRequest
                LaunchedEffect(requestedNumber) {
                    if (requestedNumber != null) dialpadOpen = true
                }
                if (dialpadOpen) {
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
                        onRequestPermissions = {},
                        showDefaultDialerBanner = false,
                        onRequestDefaultDialer = {},
                    )
                }
            }
        }
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
}
