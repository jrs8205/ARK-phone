package org.jarsi.arkphone.ui.conversation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.telecom.PhoneCaller
import org.jarsi.arkphone.ui.contactcard.ContactCardActivity
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

@AndroidEntryPoint
class ConversationActivity : ComponentActivity() {

    companion object {
        const val EXTRA_THREAD_ID = "org.jarsi.arkphone.extra.THREAD_ID"

        fun intent(context: Context, threadId: Long): Intent =
            Intent(context, ConversationActivity::class.java).putExtra(EXTRA_THREAD_ID, threadId)
    }

    @Inject lateinit var phoneCaller: PhoneCaller

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
        setContent {
            ArkPhoneTheme {
                ConversationScreen(
                    viewModel = viewModel,
                    onBack = ::finish,
                    onCall = { phoneCaller.placeCall(it) },
                    onOpenContact = { contactId ->
                        startActivity(ContactCardActivity.intent(this, contactId))
                    },
                )
            }
        }
    }
}
