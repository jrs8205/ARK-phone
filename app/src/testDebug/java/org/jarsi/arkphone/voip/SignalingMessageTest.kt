package org.jarsi.arkphone.voip

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SignalingMessageTest {

    @Test
    fun `round-trips a call offer`() {
        val original = SignalingMessage(
            type = SignalingTypes.CALL_OFFER,
            to = "phone-10pro",
            payload = buildJsonObject { put("sdp", "v=0") },
        )
        val decoded = SignalingJson.decode(SignalingJson.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `decodes a worker error message`() {
        val decoded = SignalingJson.decode(
            """{"type":"error","payload":{"code":"peer-offline","to":"phone-10pro"}}""",
        )!!
        assertEquals(SignalingTypes.ERROR, decoded.type)
        assertEquals("peer-offline", decoded.payload!!["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ignores unknown fields from newer server versions`() {
        val decoded = SignalingJson.decode("""{"type":"presence","payload":{"online":false},"v":2}""")
        assertEquals(SignalingTypes.PRESENCE, decoded!!.type)
    }

    @Test
    fun `returns null on malformed json`() {
        assertNull(SignalingJson.decode("not json"))
    }

    @Test
    fun `omits null fields when encoding`() {
        val text = SignalingJson.encode(SignalingMessage(type = SignalingTypes.HELLO))
        assertFalse(text.contains("to"))
    }
}
