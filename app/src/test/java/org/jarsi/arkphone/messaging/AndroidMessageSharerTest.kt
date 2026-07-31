package org.jarsi.arkphone.messaging

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.Message
import org.jarsi.arkphone.data.model.MessageStatus
import org.jarsi.arkphone.data.model.MmsAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AndroidMessageSharerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val sharer = AndroidMessageSharer(context, Dispatchers.Unconfined).apply {
        // The real FileProvider cannot resolve Windows-canonical paths.
        shareUriFor = { Uri.fromFile(it) }
    }

    private fun message(
        id: Long,
        body: String?,
        attachments: List<MmsAttachment> = emptyList(),
    ) = Message(
        id = id,
        threadId = 3,
        isMms = attachments.isNotEmpty(),
        address = "+358441234567",
        body = body,
        timestampMillis = id * 1000,
        incoming = true,
        status = MessageStatus.NONE,
        subscriptionId = 1,
        attachments = attachments,
    )

    private fun registerPart(uri: String, bytes: ByteArray) {
        shadowOf(context.contentResolver)
            .registerInputStream(uri.toUri(), ByteArrayInputStream(bytes))
    }

    @Test
    fun `text messages join into one plain send`() = runTest {
        val intent = sharer.shareIntent(listOf(message(1, "Moro"), message(2, "Nähdään")))
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Moro\nNähdään", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `a single image goes out as a granted stream with its text`() = runTest {
        registerPart("content://mms/part/7", byteArrayOf(1, 2, 3))
        val intent = sharer.shareIntent(
            listOf(message(1, "Kuva", listOf(MmsAttachment("content://mms/part/7", "image/jpeg")))),
        )
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals("image/jpeg", intent.type)
        assertEquals("Kuva", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        val copied = File(context.cacheDir, "mms/share").listFiles()!!.single()
        assertEquals(Uri.fromFile(copied), stream)
        assertEquals("share-7.jpg", copied.name)
        assertEquals(listOf<Byte>(1, 2, 3), copied.readBytes().toList())
    }

    @Test
    fun `several images go out as send multiple`() = runTest {
        registerPart("content://mms/part/7", byteArrayOf(1))
        registerPart("content://mms/part/8", byteArrayOf(2))
        val intent = sharer.shareIntent(
            listOf(
                message(1, "Kuvat", listOf(MmsAttachment("content://mms/part/7", "image/jpeg"))),
                message(2, null, listOf(MmsAttachment("content://mms/part/8", "image/png"))),
            ),
        )
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent!!.action)
        assertEquals("image/*", intent.type)
        val streams = IntentCompat.getParcelableArrayListExtra(
            intent,
            Intent.EXTRA_STREAM,
            Uri::class.java,
        )
        assertEquals(2, streams!!.size)
        assertEquals("Kuvat", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `an unreadable part falls back to the text share`() = runTest {
        val intent = sharer.shareIntent(
            listOf(message(1, "Kuva", listOf(MmsAttachment("content://mms/part/404", "image/jpeg")))),
        )
        assertNotNull(intent)
        assertEquals(Intent.ACTION_SEND, intent!!.action)
        assertEquals("text/plain", intent.type)
        assertEquals("Kuva", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `blank bodies and no attachments share nothing`() = runTest {
        assertNull(sharer.shareIntent(listOf(message(1, null), message(2, " "))))
        assertNull(sharer.shareIntent(emptyList()))
    }

    @Test
    fun `a new share replaces the leftovers of the previous one`() = runTest {
        registerPart("content://mms/part/7", byteArrayOf(1))
        sharer.shareIntent(
            listOf(message(1, null, listOf(MmsAttachment("content://mms/part/7", "image/jpeg")))),
        )
        registerPart("content://mms/part/8", byteArrayOf(2))
        sharer.shareIntent(
            listOf(message(2, null, listOf(MmsAttachment("content://mms/part/8", "image/png")))),
        )
        val files = File(context.cacheDir, "mms/share").listFiles()!!
        assertEquals(1, files.size)
    }
}
