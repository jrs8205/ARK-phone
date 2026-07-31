package org.jarsi.arkphone.ui.messages

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
