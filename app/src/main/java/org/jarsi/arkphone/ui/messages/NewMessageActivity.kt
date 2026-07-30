package org.jarsi.arkphone.ui.messages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.messaging.MessagingNavigator
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import javax.inject.Inject

/** The recipient a SENDTO/SEND intent already names, if any. The scheme part
 *  may carry a "?body=…" suffix that is not part of the number. */
internal fun directRecipient(intent: Intent): String? =
    intent.data?.schemeSpecificPart
        ?.substringBefore('?')
        ?.takeIf { it.isNotBlank() }

@AndroidEntryPoint
class NewMessageActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, NewMessageActivity::class.java)
    }

    @Inject lateinit var messagingNavigator: MessagingNavigator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val direct = directRecipient(intent)
        if (direct != null) {
            messagingNavigator.openConversation(this, direct)
            finish()
            return
        }
        setContent {
            ArkPhoneTheme {
                NewMessageScreen(
                    onBack = ::finish,
                    onStart = { numbers ->
                        messagingNavigator.openConversation(this, numbers)
                        finish()
                    },
                )
            }
        }
    }
}
