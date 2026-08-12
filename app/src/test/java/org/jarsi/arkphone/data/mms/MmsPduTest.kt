package org.jarsi.arkphone.data.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MmsPduTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun textBytes(text: String) = text.toByteArray(Charsets.UTF_8) + 0

    @Test
    fun `notification ind parses its fields and skips unknown headers`() {
        val pdu = bytes(0x8C, 0x82) +
            bytes(0x98) + textBytes("T1") +
            // Message-class (single short value) and expiry (value-length
            // block): both unknown to the parser, both must be skipped.
            bytes(0x8A, 0x80) +
            bytes(0x88, 0x03, 0x80, 0x01, 0x02) +
            bytes(0x89, 0x19, 0x80) + textBytes("+358441234567/TYPE=PLMN") +
            bytes(0x83) + textBytes("http://mmsc/x")

        val notification = parseNotificationInd(pdu)!!

        assertEquals("T1", notification.transactionId)
        assertEquals("http://mmsc/x", notification.contentLocation)
        assertEquals("+358441234567", notification.from)
    }

    @Test
    fun `a send req round trips through the retrieve parser`() {
        val parts = listOf(
            MmsPart("text/plain", "Moro maailma".toByteArray(), null),
            MmsPart("image/jpeg", bytes(1, 2, 3, 4), null),
        )

        val pdu = composeSendReq("+358441234567", listOf("+358400000000"), parts)
        val conf = parseRetrieveConf(pdu)!!

        assertEquals("+358441234567", conf.from)
        assertEquals(listOf("+358400000000"), conf.to)
        assertEquals(listOf("text/plain", "image/jpeg"), conf.parts.map { it.mimeType })
        assertArrayEquals("Moro maailma".toByteArray(), conf.parts[0].body)
        assertArrayEquals(bytes(1, 2, 3, 4), conf.parts[1].body)
    }

    @Test
    fun `a send req without sender uses the insert address token`() {
        val pdu = composeSendReq(
            null,
            listOf("+358400000000"),
            listOf(MmsPart("text/plain", "x".toByteArray(), null)),
        )
        val conf = parseRetrieveConf(pdu)!!
        assertNull(conf.from)
    }

    @Test
    fun `a group send req carries every recipient`() {
        val pdu = composeSendReq(
            null,
            listOf("+358400000000", "+358411111111"),
            listOf(MmsPart("text/plain", "Moro kaikille".toByteArray(), null)),
        )
        val conf = parseRetrieveConf(pdu)!!
        assertEquals(listOf("+358400000000", "+358411111111"), conf.to)
    }

    @Test
    fun `a retrieve conf collects cc and bcc recipients too`() {
        // iPhone clients and MMSC rewrites list group members in Cc; a
        // parser that only reads To loses them and the group looks 1:1.
        val headers = textBytes("text/plain")
        val data = "Kaikille".toByteArray()
        val pdu = bytes(0x8C, 0x84) +
            bytes(0x89, 0x19, 0x80) + textBytes("+358441234567/TYPE=PLMN") +
            bytes(0x97) + textBytes("+358400000000/TYPE=PLMN") +
            bytes(0x82) + textBytes("+358411111111/TYPE=PLMN") +
            bytes(0x81) + textBytes("+358422222222/TYPE=PLMN") +
            bytes(0x84, 0xA3) +
            bytes(0x01) +
            bytes(headers.size, data.size) + headers + data

        val conf = parseRetrieveConf(pdu)!!

        assertEquals(listOf("+358400000000"), conf.to)
        assertEquals(listOf("+358411111111", "+358422222222"), conf.cc)
    }

    /** A carrier-shaped m-retrieve-conf: multipart.related with start/type
     *  parameters, a SMIL part, per-part content-type parameters and
     *  Content-ID/Content-Location headers — none of which our own
     *  composeSendReq produces. */
    private fun carrierRetrieveConf(contentTypeLongForm: Boolean): ByteArray {
        val startParam = bytes(0x8A) + textBytes("<smil>")
        val typeParam = bytes(0x89) + textBytes("application/smil")
        val contentTypeValue = bytes(0xB3) + startParam + typeParam
        val contentTypeHeader = if (contentTypeLongForm) {
            bytes(0x84, 0x1F, contentTypeValue.size) + contentTypeValue
        } else {
            bytes(0x84, contentTypeValue.size) + contentTypeValue
        }

        val smilContentType = textBytes("application/smil") + bytes(0x81, 0xEA)
        val smilHeaders = bytes(smilContentType.size) + smilContentType +
            bytes(0xC0, 0x22) + textBytes("<smil>")
        val smilData = "<smil><body></body></smil>".toByteArray()

        val imageContentType = bytes(0x9E, 0x85) + textBytes("IMG_001.jpg")
        val imageHeaders = bytes(imageContentType.size) + imageContentType +
            bytes(0x8E) + textBytes("IMG_001.jpg") +
            bytes(0xC0, 0x22) + textBytes("<img>")
        val imageData = bytes(0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4)

        val textContentType = bytes(0x83, 0x81, 0xEA)
        val textHeaders = bytes(textContentType.size) + textContentType +
            bytes(0x8E) + textBytes("text_0.txt")
        val textData = "Terve!".toByteArray()

        return bytes(0x8C, 0x84) +
            bytes(0x98) + textBytes("tr1") +
            bytes(0x8D, 0x92) +
            bytes(0x8B) + textBytes("12@mmsc") +
            bytes(0x85, 0x04, 0x68, 0x89, 0x00, 0x00) +
            bytes(0x89, 0x19, 0x80) + textBytes("+358445552841/TYPE=PLMN") +
            bytes(0x96, 0x06, 0xEA) + textBytes("Kuva") +
            bytes(0x8A, 0x80) +
            bytes(0x8F, 0x81) +
            bytes(0x86, 0x81) +
            bytes(0x90, 0x81) +
            contentTypeHeader +
            bytes(0x03) +
            bytes(smilHeaders.size, smilData.size) + smilHeaders + smilData +
            bytes(imageHeaders.size, imageData.size) + imageHeaders + imageData +
            bytes(textHeaders.size, textData.size) + textHeaders + textData
    }

    @Test
    fun `a carrier shaped retrieve conf parses its parts`() {
        val conf = parseRetrieveConf(carrierRetrieveConf(contentTypeLongForm = false))!!

        assertEquals("+358445552841", conf.from)
        assertEquals(
            listOf("application/smil", "image/jpeg", "text/plain"),
            conf.parts.map { it.mimeType },
        )
        assertArrayEquals(bytes(0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3, 4), conf.parts[1].body)
        assertArrayEquals("Terve!".toByteArray(), conf.parts[2].body)
    }

    @Test
    fun `a retrieve conf with the long content type form parses too`() {
        val conf = parseRetrieveConf(carrierRetrieveConf(contentTypeLongForm = true))!!
        assertEquals(3, conf.parts.size)
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(parseNotificationInd(bytes(1, 2, 3)))
        assertNull(parseNotificationInd(ByteArray(0)))
        assertNull(parseRetrieveConf(ByteArray(0)))
        assertNull(parseRetrieveConf(bytes(0x8C, 0x82)))
    }
}
