package org.jarsi.arkphone.ui.messages

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannedString
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NewMessageIntentTest {

    @Test
    fun `shared body comes from the text extra`() {
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "Moro")
        assertEquals("Moro", sharedBody(intent))
    }

    @Test
    fun `shared body decodes the sendto body query`() {
        val intent = Intent(Intent.ACTION_SENDTO, "smsto:0441234567?body=Moro%20taas".toUri())
        assertEquals("Moro taas", sharedBody(intent))
        assertEquals("0441234567", directRecipient(intent))
    }

    @Test
    fun `shared body accepts a styled char sequence`() {
        val intent = Intent(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_TEXT, SpannedString.valueOf("Moro") as CharSequence)
        assertEquals("Moro", sharedBody(intent))
    }

    @Test
    fun `shared body keeps an encoded ampersand in the sendto body`() {
        val intent = Intent(Intent.ACTION_SENDTO, "smsto:0441234567?body=Tom%20%26%20Jerry".toUri())
        assertEquals("Tom & Jerry", sharedBody(intent))
        assertEquals("0441234567", directRecipient(intent))
    }

    @Test
    fun `a shared image is staged into an app owned cache copy`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = "content://ext/img/9".toUri()
        shadowOf(context.contentResolver)
            .registerInputStream(source, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val staged = stageSharedImage(context, source)
        assertNotNull(staged)
        assertEquals("file", staged!!.scheme)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(staged.path!!).readBytes())
    }

    @Test
    fun `an unreadable shared image stages to null`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(stageSharedImage(context, "content://ext/img/404".toUri()))
    }

    @Test
    fun `no shared image stages to null`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(stageSharedImage(context, null))
    }

    @Test
    fun `no body anywhere is null`() {
        assertNull(sharedBody(Intent(Intent.ACTION_SEND)))
        assertNull(sharedBody(Intent(Intent.ACTION_SENDTO, "smsto:0441234567".toUri())))
    }

    @Test
    fun `shared image comes from the stream extra`() {
        val uri = "content://media/external/images/1".toUri()
        val intent = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        assertEquals(uri, sharedImage(intent))
    }

    @Test
    fun `no stream extra is null`() {
        assertNull(sharedImage(Intent(Intent.ACTION_SEND)))
    }
}
