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
import java.io.File
import javax.inject.Inject

/** The recipients a SENDTO/SEND intent already names. The scheme part may
 *  carry a "?body=…" suffix that is not part of the numbers, and multiple
 *  recipients arrive as one semicolon- or comma-separated string. */
internal fun directRecipients(intent: Intent): List<String> =
    intent.data?.schemeSpecificPart
        ?.substringBefore('?')
        ?.split(';', ',')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

/** The prefilled message text a SEND/SENDTO intent carries, if any. */
internal fun sharedBody(intent: Intent): String? {
    // Senders may put a styled CharSequence in EXTRA_TEXT; getStringExtra
    // would return null for those.
    intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ?.takeIf { it.isNotBlank() }?.let { return it }
    // Split the still-encoded form so an encoded '&' inside the body value
    // survives, then decode exactly once.
    return intent.data?.encodedSchemeSpecificPart
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

/** Copies a shared image into an app-owned cache file while the sender's
 *  URI grant is still alive — the grant dies when this activity finishes,
 *  and the conversation reads the image only later at send time. */
internal fun stageSharedImage(context: Context, source: Uri?): Uri? {
    source ?: return null
    return runCatching {
        val dir = File(context.cacheDir, "shared").apply {
            deleteRecursively()
            mkdirs()
        }
        val extension = when (context.contentResolver.getType(source)) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val file = File(dir, "incoming.$extension")
        checkNotNull(context.contentResolver.openInputStream(source)).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        Uri.fromFile(file)
    }.getOrNull()
}

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
        val image = stageSharedImage(this, sharedImage(intent))
        val direct = directRecipients(intent)
        if (direct.isNotEmpty()) {
            messagingNavigator.openConversation(this, direct, body, image)
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
