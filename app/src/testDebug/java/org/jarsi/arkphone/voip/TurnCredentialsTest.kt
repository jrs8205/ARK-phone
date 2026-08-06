package org.jarsi.arkphone.voip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnCredentialsTest {

    @Test
    fun `parses the worker response into ice servers`() {
        val body = """
            {"iceServers":[
              {"urls":["stun:stun.cloudflare.com:3478"]},
              {"urls":["turn:turn.cloudflare.com:3478?transport=udp",
                       "turns:turn.cloudflare.com:5349?transport=tcp"],
               "username":"u","credential":"c"}
            ]}
        """.trimIndent()
        val servers = TurnCredentialsParser.parse(body)!!
        assertEquals(2, servers.size)
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), servers[0].urls)
        assertNull(servers[0].username)
        assertEquals("u", servers[1].username)
        assertEquals("c", servers[1].credential)
    }

    @Test
    fun `accepts a single string urls field`() {
        val servers = TurnCredentialsParser.parse(
            """{"iceServers":[{"urls":"stun:stun.cloudflare.com:3478"}]}""",
        )!!
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), servers[0].urls)
    }

    @Test
    fun `returns null on malformed input`() {
        assertNull(TurnCredentialsParser.parse("oops"))
    }
}
