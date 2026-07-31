package org.jarsi.arkphone.messaging

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.di.IoDispatcher
import java.io.File
import javax.inject.Inject

class AndroidMessageSharer @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MessageSharer {

    // androidx FileProvider joins root paths with '/', which never matches the
    // '\'-separated canonical paths of a Windows-hosted unit test; tests swap
    // this hook instead of going through the real provider.
    internal var shareUriFor: (File) -> Uri = { file ->
        FileProvider.getUriForFile(context, MmsFileProvider.AUTHORITY, file)
    }

    override suspend fun shareIntent(messages: List<Message>): Intent? =
        withContext(ioDispatcher) {
            val text = messages.mapNotNull { it.body?.takeIf(String::isNotBlank) }
                .joinToString("\n")
                .takeIf { it.isNotEmpty() }
            val images = copyPartsToShareDir(messages)
            when {
                images.isEmpty() && text == null -> null
                images.isEmpty() -> Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                images.size == 1 -> Intent(Intent.ACTION_SEND).apply {
                    val (uri, mimeType) = images.single()
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                else -> Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(
                        Intent.EXTRA_STREAM,
                        ArrayList(images.map { it.first }),
                    )
                    text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }

    /** Other apps cannot read content://mms/part, so every shared image is
     *  copied into the cache directory served by [MmsFileProvider]. The
     *  directory is wiped on each share so leftovers cannot pile up. */
    private fun copyPartsToShareDir(messages: List<Message>): List<Pair<Uri, String>> {
        val shareDir = File(context.cacheDir, SHARE_DIR)
        shareDir.deleteRecursively()
        val copied = mutableListOf<Pair<Uri, String>>()
        val attachments = messages.flatMap { it.attachments }
            .filter { it.mimeType.startsWith("image/") }
        attachments.forEach { attachment ->
            val partUri = attachment.partUri.toUri()
            val name = "share-${partUri.lastPathSegment}.${extensionOf(attachment.mimeType)}"
            val file = File(shareDir, name)
            val ok = runCatching {
                shareDir.mkdirs()
                context.contentResolver.openInputStream(partUri)!!.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
            }.isSuccess
            if (ok) {
                copied += shareUriFor(file) to attachment.mimeType
            } else {
                file.delete()
            }
        }
        return copied
    }

    private fun extensionOf(mimeType: String) = when (mimeType) {
        "image/jpeg" -> "jpg"
        else -> mimeType.substringAfter('/', "bin")
    }

    private companion object {
        const val SHARE_DIR = "mms/share"
    }
}
