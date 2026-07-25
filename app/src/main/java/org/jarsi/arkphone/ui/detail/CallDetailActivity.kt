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
import org.jarsi.arkphone.telecom.PhoneCaller
import org.jarsi.arkphone.telecom.WhatsAppCallLauncher
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class CallDetailActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_NUMBER = "org.jarsi.arkphone.extra.DETAIL_NUMBER"

        fun intent(context: Context, number: String): Intent =
            Intent(context, CallDetailActivity::class.java).putExtra(EXTRA_NUMBER, number)
    }

    @Inject lateinit var phoneCaller: PhoneCaller

    @Inject lateinit var whatsAppCallLauncher: WhatsAppCallLauncher

    private val viewModel: CallDetailViewModel by viewModels()

    private val writeCallLogLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.onDeleteHistory() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val number = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        if (number.isBlank()) {
            finish()
            return
        }
        viewModel.setNumber(number)
        setContent {
            ArkPhoneTheme {
                CallDetailScreen(
                    viewModel = viewModel,
                    onBack = ::finish,
                    onCall = { phoneCaller.placeCall(it) },
                    onMessage = ::openMessagingApp,
                    onEditBeforeCall = ::openKeypadPrefilled,
                    onDeleteHistory = ::deleteHistoryWithPermission,
                    onWhatsAppCall = { target, name ->
                        whatsAppCallLauncher.startCall(target.takeIf { it.isNotBlank() }, name)
                    },
                )
            }
        }
    }

    private fun openMessagingApp(number: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))
        }
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
