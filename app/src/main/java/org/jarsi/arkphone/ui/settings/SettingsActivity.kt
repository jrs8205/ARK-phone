package org.jarsi.arkphone.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArkPhoneTheme {
                var showSimInfo by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showSimInfo) { showSimInfo = false }
                if (showSimInfo) {
                    SimInfoScreen(onBack = { showSimInfo = false })
                } else {
                    SettingsScreen(
                        onBack = ::finish,
                        onOpenSimInfo = { showSimInfo = true },
                    )
                }
            }
        }
    }
}
