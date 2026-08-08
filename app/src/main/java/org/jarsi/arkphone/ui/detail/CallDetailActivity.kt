package org.jarsi.arkphone.ui.detail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.MainActivity
import org.jarsi.arkphone.messaging.MessagingNavigator
import org.jarsi.arkphone.telecom.CallRouter
import org.jarsi.arkphone.telecom.WhatsAppCallLauncher
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class CallDetailActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_NUMBER = "org.jarsi.arkphone.extra.DETAIL_NUMBER"
        private const val EXTRA_NAME = "org.jarsi.arkphone.extra.DETAIL_NAME"

        fun intent(context: Context, number: String): Intent =
            Intent(context, CallDetailActivity::class.java).putExtra(EXTRA_NUMBER, number)

        /** Details for a WhatsApp caller known only by display name. */
        fun nameIntent(context: Context, name: String): Intent =
            Intent(context, CallDetailActivity::class.java).putExtra(EXTRA_NAME, name)
    }

    @Inject lateinit var callRouter: CallRouter

    @Inject lateinit var whatsAppCallLauncher: WhatsAppCallLauncher

    @Inject lateinit var messagingNavigator: MessagingNavigator

    private val viewModel: CallDetailViewModel by viewModels()

    private val writeCallLogLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.onDeleteHistory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        when {
            number.isNotBlank() -> viewModel.setNumber(number)
            name.isNotBlank() -> viewModel.setNameKey(name)
            else -> {
                finish()
                return
            }
        }
        setContent {
            ArkPhoneTheme {
                CallDetailScreen(
                    viewModel = viewModel,
                    onBack = ::finish,
                    onCall = { callRouter.placeCall(it) },
                    onMessage = ::openMessagingApp,
                    onEditBeforeCall = ::openKeypadPrefilled,
                    onDeleteHistory = ::deleteHistoryWithPermission,
                    onWhatsAppCall = { target, name ->
                        whatsAppCallLauncher.startCall(target.takeIf { it.isNotBlank() }, name, null)
                    },
                )
            }
        }
    }

    private fun openMessagingApp(number: String) {
        messagingNavigator.openConversation(this, number)
    }

    private fun openKeypadPrefilled(number: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_DIAL)
                .setData(Uri.fromParts("tel", number, null)),
        )
    }

    private fun deleteHistoryWithPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.WRITE_CALL_LOG,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onDeleteHistory()
        } else {
            writeCallLogLauncher.launch(Manifest.permission.WRITE_CALL_LOG)
        }
    }
}
