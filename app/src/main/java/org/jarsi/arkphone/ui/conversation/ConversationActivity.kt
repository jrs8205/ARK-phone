package org.jarsi.arkphone.ui.conversation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.jarsi.arkphone.messaging.MmsDownloader
import org.jarsi.arkphone.telecom.PhoneCaller
import org.jarsi.arkphone.ui.contactcard.ContactCardActivity
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class ConversationActivity : ComponentActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "org.jarsi.arkphone.extra.THREAD_ID"
        const val EXTRA_INITIAL_BODY = "org.jarsi.arkphone.extra.INITIAL_BODY"
        const val EXTRA_INITIAL_IMAGE = "org.jarsi.arkphone.extra.INITIAL_IMAGE"

        fun intent(
            context: Context,
            threadId: Long,
            initialBody: String? = null,
            initialImage: Uri? = null,
        ): Intent =
            Intent(context, ConversationActivity::class.java)
                .putExtra(EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_INITIAL_BODY, initialBody)
                .putExtra(EXTRA_INITIAL_IMAGE, initialImage)
    }

    @Inject lateinit var phoneCaller: PhoneCaller

    @Inject lateinit var mmsDownloader: MmsDownloader

    private val viewModel: ConversationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1L)
        if (threadId < 0) {
            finish()
            return
        }
        viewModel.open(threadId)
        if (savedInstanceState == null) {
            IntentCompat.getParcelableExtra(intent, EXTRA_INITIAL_IMAGE, Uri::class.java)
                ?.let(viewModel::onAttachImage)
        }
        setContent {
            ArkPhoneTheme {
                ConversationScreen(
                    viewModel = viewModel,
                    initialComposerText = intent.getStringExtra(EXTRA_INITIAL_BODY).orEmpty(),
                    onBack = ::finish,
                    onCall = { phoneCaller.placeCall(it) },
                    onOpenContact = { contactId ->
                        startActivity(ContactCardActivity.intent(this, contactId))
                    },
                    onRetryDownload = { messageId ->
                        lifecycleScope.launch { mmsDownloader.retryDownload(messageId) }
                    },
                )
            }
        }
    }
}
