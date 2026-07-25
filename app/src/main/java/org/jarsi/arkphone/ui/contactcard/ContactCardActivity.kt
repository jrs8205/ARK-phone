package org.jarsi.arkphone.ui.contactcard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.telecom.PhoneCaller
import org.jarsi.arkphone.ui.detail.CallDetailActivity
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class ContactCardActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_CONTACT_ID = "org.jarsi.arkphone.extra.CONTACT_ID"

        fun intent(context: Context, contactId: Long): Intent =
            Intent(context, ContactCardActivity::class.java).putExtra(EXTRA_CONTACT_ID, contactId)
    }

    @Inject lateinit var phoneCaller: PhoneCaller

    private val viewModel: ContactCardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1L)
        if (contactId < 0) {
            finish()
            return
        }
        viewModel.load(contactId)
        setContent {
            ArkPhoneTheme {
                ContactCardScreen(
                    viewModel = viewModel,
                    onBack = ::finish,
                    onCall = { phoneCaller.placeCall(it) },
                    onMessage = { open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", it, null))) },
                    onEmail = { open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", it, null))) },
                    onOpenAddress = {
                        open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(it))))
                    },
                    onOpenWebsite = { url ->
                        val target = if (url.contains("://")) url else "https://$url"
                        open(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    },
                    onOpenCallHistory = {
                        startActivity(CallDetailActivity.intent(this, it))
                    },
                )
            }
        }
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
    }
}
