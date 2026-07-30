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

        val pdu = composeSendReq("+358441234567", "+358400000000", parts)
        val conf = parseRetrieveConf(pdu)!!

        assertEquals("+358441234567", conf.from)
        assertEquals(listOf("+358400000000"), conf.to)
        assertEquals(listOf("text/plain", "image/jpeg"), conf.parts.map { it.mimeType })
        assertArrayEquals("Moro maailma".toByteArray(), conf.parts[0].body)
        assertArrayEquals(bytes(1, 2, 3, 4), conf.parts[1].body)
    }

    @Test
    fun `a send req without sender uses the insert address token`() {
        val pdu = composeSendReq(null, "+358400000000", listOf(MmsPart("text/plain", "x".toByteArray(), null)))
        val conf = parseRetrieveConf(pdu)!!
        assertNull(conf.from)
    }

    @Test
    fun `garbage is rejected`() {
        assertNull(parseNotificationInd(bytes(1, 2, 3)))
        assertNull(parseNotificationInd(ByteArray(0)))
        assertNull(parseRetrieveConf(ByteArray(0)))
        assertNull(parseRetrieveConf(bytes(0x8C, 0x82)))
    }
}
