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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.messaging.MessagingNavigator
import org.jarsi.arkphone.ui.theme.ArkPhoneTheme
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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

/** Stages a share and opens its conversation, testable without the
 *  activity. It runs in the application scope: a back press or system
 *  destroy mid-copy used to cancel the lifecycle coroutine and silently
 *  drop the share with the conversation never opening. */
@Singleton
class SharedImageOpener @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val messagingNavigator: MessagingNavigator,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** A recreated sharing activity (rotation mid-copy) calls open() again;
     *  the first staging must not race a second one over the same files. */
    private var opening = false

    fun open(
        numbers: List<String>,
        body: String?,
        source: Uri?,
        host: () -> Context?,
        onStaged: () -> Unit = {},
    ) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                val image = withContext(ioDispatcher) { stageSharedImage(appContext, source) }
                messagingNavigator.openConversation(host() ?: appContext, numbers, body, image)
            } finally {
                opening = false
                onStaged()
            }
        }
    }
}

@AndroidEntryPoint
class NewMessageActivity : ComponentActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, NewMessageActivity::class.java)
    }

    @Inject lateinit var sharedImageOpener: SharedImageOpener

    private var navigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val body = sharedBody(intent)
        val direct = directRecipients(intent)
        if (direct.isNotEmpty()) {
            openStaged(direct, body)
            return
        }
        setContent {
            ArkPhoneTheme {
                NewMessageScreen(
                    onBack = ::finish,
                    onStart = { numbers -> openStaged(numbers, body) },
                )
            }
        }
    }

    /** Stages the shared image off the main thread — a multi-megabyte camera
     *  share used to block onCreate — but still before finish(), which kills
     *  the sender's URI grant. */
    private fun openStaged(numbers: List<String>, body: String?) {
        if (navigating) return
        navigating = true
        sharedImageOpener.open(
            numbers = numbers,
            body = body,
            source = sharedImage(intent),
            host = { takeUnless { isDestroyed || isFinishing } },
            onStaged = ::finish,
        )
    }
}
