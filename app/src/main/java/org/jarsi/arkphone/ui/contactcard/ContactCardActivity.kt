package org.jarsi.arkphone.ui.contactcard

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
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

    private var contactId: Long = -1L

    override fun onResume() {
        super.onResume()
        // Reload after a possible edit in the system contact editor.
        if (contactId >= 0) viewModel.load(contactId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        contactId = intent.getLongExtra(EXTRA_CONTACT_ID, -1L)
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
                    onOpenAppAction = { action ->
                        // The mimetype is registered by exactly one app, so the
                        // system routes this to WhatsApp/Signal/… without a package.
                        open(
                            Intent(Intent.ACTION_VIEW).setDataAndType(
                                ContentUris.withAppendedId(
                                    ContactsContract.Data.CONTENT_URI,
                                    action.dataId,
                                ),
                                action.mimeType,
                            ),
                        )
                    },
                    onEdit = { details ->
                        val uri = details.lookupKey?.let { key ->
                            ContactsContract.Contacts.getLookupUri(details.id, key)
                        } ?: ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI,
                            details.id,
                        )
                        open(
                            Intent(Intent.ACTION_EDIT)
                                .setDataAndType(uri, ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                .putExtra("finishActivityOnSaveCompleted", true),
                        )
                    },
                    onShare = { details ->
                        details.lookupKey?.let { key ->
                            val vcard = Uri.withAppendedPath(
                                ContactsContract.Contacts.CONTENT_VCARD_URI,
                                key,
                            )
                            open(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND)
                                        .setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
                                        .putExtra(Intent.EXTRA_STREAM, vcard),
                                    null,
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    private fun open(intent: Intent) {
        runCatching { startActivity(intent) }
    }
}
