package org.jarsi.arkphone.messaging

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.FakeTelephonyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidMmsSenderTest {

    private class RecordingTransport : MmsTransport {
        data class SentPdu(val file: File, val rowUri: Uri, val subscriptionId: Int)
        val sent = mutableListOf<SentPdu>()
        override fun sendPdu(pduFile: File, rowUri: Uri, subscriptionId: Int) {
            sent += SentPdu(pduFile, rowUri, subscriptionId)
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val transport = RecordingTransport()
    private lateinit var provider: FakeTelephonyProvider
    private lateinit var sender: AndroidMmsSender

    @Before
    fun setUp() {
        provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms", provider)
        sender = AndroidMmsSender(
            context = context,
            imageShrinker = ImageShrinker(context),
            transport = transport,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `a failed handoff deletes the staged pdu`() = runTest {
        val failing = AndroidMmsSender(
            context = context,
            imageShrinker = ImageShrinker(context),
            transport = { _, _, _ -> throw IllegalStateException("no mms service") },
            ioDispatcher = Dispatchers.Unconfined,
        )

        failing.send(listOf("+358400000000"), "Moro", null)

        val staged = File(context.cacheDir, "mms").listFiles().orEmpty()
            .filter { it.name.startsWith("mms-send-") }
        assertEquals(emptyList<File>(), staged)
    }

    @Test
    fun `send stores the outbox row with its parts`() = runTest {
        val image = File(context.cacheDir, "attachment.jpg")
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFFAA3366.toInt())
            image.outputStream().use { compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }

        val rowUri = sender.send(listOf("+358400000000"), "Saate", Uri.fromFile(image))

        assertNotNull(rowUri)
        val row = provider.mmsRows.single()
        assertEquals(Telephony.Mms.MESSAGE_BOX_OUTBOX, row.getAsInteger("msg_box"))
        assertEquals(42L, row.getAsLong("thread_id"))
        assertEquals(132, row.getAsInteger("m_type"))
        val partTypes = provider.mmsPartRows.map { it.second.getAsString("ct") }
        assertTrue("image/jpeg" in partTypes)
        assertTrue("text/plain" in partTypes)
        val textPart = provider.mmsPartRows.single { it.second.getAsString("ct") == "text/plain" }
        assertEquals("Saate", textPart.second.getAsString("text"))
        val (_, addr) = provider.mmsAddrRows.single()
        assertEquals("+358400000000", addr.getAsString("address"))
        assertEquals(151, addr.getAsInteger("type"))
        with(transport.sent.single()) {
            assertEquals(rowUri, this.rowUri)
            assertTrue(file.exists() && file.length() > 0)
        }
    }

    @Test
    fun `an unreadable image aborts the send`() = runTest {
        val broken = File(context.cacheDir, "broken.jpg")
        broken.writeBytes("garbage".toByteArray())

        val rowUri = sender.send(listOf("+358400000000"), null, Uri.fromFile(broken))

        assertEquals(null, rowUri)
        assertTrue(provider.mmsRows.isEmpty())
    }

    @Test
    fun `a text only group send stores an addr row per recipient`() = runTest {
        val rowUri = sender.send(
            listOf("+358400000000", "+358411111111"),
            "Moro kaikille",
            imageUri = null,
        )

        assertNotNull(rowUri)
        assertEquals(
            listOf("+358400000000", "+358411111111"),
            provider.mmsAddrRows.map { it.second.getAsString("address") },
        )
        val part = provider.mmsPartRows.single().second
        assertEquals("text/plain", part.getAsString("ct"))
        assertEquals("Moro kaikille", part.getAsString("text"))
        assertEquals(rowUri, transport.sent.single().rowUri)
    }

    @Test
    fun `a send on a chosen sim stores it and rides it to the transport`() = runTest {
        val rowUri = sender.send(
            listOf("+358400000000", "+358411111111"),
            "Moro",
            imageUri = null,
            subscriptionId = 5,
        )

        assertNotNull(rowUri)
        assertEquals(5, provider.mmsRows.single().getAsInteger("sub_id"))
        assertEquals(5, transport.sent.single().subscriptionId)
    }

    @Test
    fun `a display formatted recipient is normalized before the send`() = runTest {
        val rowUri = sender.send(
            listOf("+358 44 5552841", "0443342131"),
            "Moro",
            imageUri = null,
        )

        assertNotNull(rowUri)
        assertEquals(
            listOf("+358445552841", "0443342131"),
            provider.mmsAddrRows.map { it.second.getAsString("address") },
        )
        val pduText = transport.sent.single().file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("+358445552841/TYPE=PLMN" in pduText)
        assertTrue("+358 44" !in pduText)
    }

    @Test
    fun `a send with neither text nor image starts nothing`() = runTest {
        assertEquals(null, sender.send(listOf("+358400000000"), "  ", imageUri = null))
        assertTrue(provider.mmsRows.isEmpty())
    }
}
