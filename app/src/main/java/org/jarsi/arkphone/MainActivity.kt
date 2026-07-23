package org.jarsi.arkphone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.ui.navigation.MainScreen
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArkPhoneTheme {
                MainScreen(
                    onCall = {},
                    onOpenDialpad = {},
                    onRequestPermissions = {},
                    showDefaultDialerBanner = false,
                    onRequestDefaultDialer = {},
                )
            }
        }
    }
}
