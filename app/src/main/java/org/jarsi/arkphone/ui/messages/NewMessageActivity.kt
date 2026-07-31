package org.jarsi.arkphone.ui.messages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
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

/** The prefilled message text a SEND/SENDTO intent carries, if any. */
internal fun sharedBody(intent: Intent): String? {
    intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
    return intent.data?.schemeSpecificPart
        ?.substringAfter('?', "")
        ?.split('&')
        ?.firstOrNull { it.startsWith("body=") }
        ?.removePrefix("body=")
        ?.let(Uri::decode)
        ?.takeIf { it.isNotBlank() }
}

/** The image a SEND intent shares, if any. */
internal fun sharedImage(intent: Intent): Uri? =
    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)

@AndroidEntryPoint
class NewMessageActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, NewMessageActivity::class.java)
    }

    @Inject lateinit var messagingNavigator: MessagingNavigator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val body = sharedBody(intent)
        val image = sharedImage(intent)
        val direct = directRecipient(intent)
        if (direct != null) {
            messagingNavigator.openConversation(this, listOf(direct), body, image)
            finish()
            return
        }
        setContent {
            ArkPhoneTheme {
                NewMessageScreen(
                    onBack = ::finish,
                    onStart = { numbers ->
                        messagingNavigator.openConversation(this, numbers, body, image)
                        finish()
                    },
                )
            }
        }
    }
}
