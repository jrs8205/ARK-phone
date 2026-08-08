package org.jarsi.arkphone.voip

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared by every worker-HTTP test in this source set. */
class FakeArkHttp : ArkHttp {
    data class Call(val method: String, val url: String, val body: String?, val bearer: String?)

    val calls = mutableListOf<Call>()
    var response: ArkHttpResponse? = null

    override suspend fun get(url: String, bearer: String?): ArkHttpResponse? {
        calls += Call("GET", url, null, bearer)
        return response
    }

    override suspend fun postJson(url: String, json: String, bearer: String?): ArkHttpResponse? {
        calls += Call("POST", url, json, bearer)
        return response
    }
}

class ArkAccountClientTest {

    private val http = FakeArkHttp()
    private val client = ArkAccountClient(http, "https://w")

    @Test
    fun registrationPostsTheNicknameKeyAndTokenAndReadsTheCodeBack() = runTest {
        http.response = ArkHttpResponse(200, """{"code":"ARK-7K3M-Q2FP","deviceToken":"tok"}""")
        val registration = client.register("Jarsi", "pk-test", "fcm-1")
        assertEquals(ArkRegistration("ARK-7K3M-Q2FP", "tok"), registration)
        val call = http.calls.single()
        assertEquals("https://w/register", call.url)
        assertNull(call.bearer)
        assertTrue(call.body!!.contains("\"nickname\":\"Jarsi\""))
        assertTrue(call.body.contains("\"publicKey\":\"pk-test\""))
        assertTrue(call.body.contains("\"fcmToken\":\"fcm-1\""))
    }

    @Test
    fun anAbsentFcmTokenIsOmittedRatherThanSentAsAnEmptyString() = runTest {
        http.response = ArkHttpResponse(200, """{"code":"ARK-7K3M-Q2FP","deviceToken":"tok"}""")
        client.register("Jarsi", "pk-test", null)
        assertFalse(http.calls.single().body!!.contains("fcmToken"))
    }

    @Test
    fun aRejectedOrRateLimitedRegistrationIsAFailureNotACrash() = runTest {
        http.response = ArkHttpResponse(429, "Rate limited")
        assertNull(client.register("Jarsi", "pk-test", null))
        http.response = ArkHttpResponse(400, "Bad request")
        assertNull(client.register("Jarsi", "pk-test", null))
        http.response = null
        assertNull(client.register("Jarsi", "pk-test", null))
    }

    @Test
    fun aLookupUsesTheBearerAndReturnsTheNicknameAndKey() = runTest {
        http.response = ArkHttpResponse(
            200,
            """{"code":"ARK-BBBB-BBBB","nickname":"B","publicKey":"pk-b"}""",
        )
        val account = client.lookUp("ARK-BBBB-BBBB", "ARK-AAAA-AAAA.tok")
        assertEquals(ArkAccount("ARK-BBBB-BBBB", "B", "pk-b"), account)
        val call = http.calls.single()
        assertEquals("https://w/account/ARK-BBBB-BBBB", call.url)
        assertEquals("ARK-AAAA-AAAA.tok", call.bearer)
    }

    @Test
    fun anUnregisteredCodeIsA404AndYieldsNull() = runTest {
        http.response = ArkHttpResponse(404, "")
        assertNull(client.lookUp("ARK-BBBB-BBBB", "ARK-AAAA-AAAA.tok"))
    }

    @Test
    fun anFcmTokenUpdateIsA204() = runTest {
        http.response = ArkHttpResponse(204, "")
        assertTrue(client.updateFcmToken("fcm-2", "ARK-AAAA-AAAA.tok"))
        val call = http.calls.single()
        assertEquals("https://w/account/fcm-token", call.url)
        assertEquals("""{"fcmToken":"fcm-2"}""", call.body)
    }

    @Test
    fun aRejectedFcmTokenUpdateIsFalse() = runTest {
        http.response = ArkHttpResponse(400, "Bad request")
        assertFalse(client.updateFcmToken("fcm-2", "ARK-AAAA-AAAA.tok"))
    }

    @Test
    fun turnCredentialsAreParsedFromTheProxiedBody() = runTest {
        http.response = ArkHttpResponse(
            200,
            """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478"]}]}""",
        )
        val servers = client.turnCredentials("ARK-AAAA-AAAA.tok")
        assertEquals(listOf("stun:stun.cloudflare.com:3478"), servers!!.single().urls)
        assertEquals("https://w/turn-credentials", http.calls.single().url)
    }

    @Test
    fun anUnavailableTurnUpstreamYieldsNull() = runTest {
        http.response = ArkHttpResponse(502, "TURN credentials unavailable")
        assertNull(client.turnCredentials("ARK-AAAA-AAAA.tok"))
    }
}
