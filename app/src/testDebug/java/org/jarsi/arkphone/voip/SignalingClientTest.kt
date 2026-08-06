package org.jarsi.arkphone.voip

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalingClientTest {

    private class FakeHandle : WebSocketHandle {
        val sent = mutableListOf<String>()
        var closed = false
        override fun send(text: String): Boolean { sent.add(text); return true }
        override fun close() { closed = true }
    }

    private class FakeConnector : WebSocketConnector {
        val handles = mutableListOf<FakeHandle>()
        var lastOnText: ((String) -> Unit)? = null
        var lastOnClosed: (() -> Unit)? = null
        override fun connect(
            url: String,
            onText: (String) -> Unit,
            onClosed: () -> Unit,
        ): WebSocketHandle {
            lastOnText = onText
            lastOnClosed = onClosed
            return FakeHandle().also { handles.add(it) }
        }

        fun serverSends(message: SignalingMessage) {
            lastOnText!!(SignalingJson.encode(message))
        }
    }

    @Test
    fun `sends hello on start and tracks peer presence from the ack`() = runTest {
        val connector = FakeConnector()
        val client = SignalingClient(connector, "https://w", "phone-8a", "phone-10pro", this)
        client.start()
        val hello = SignalingJson.decode(connector.handles.single().sent.single())!!
        assertEquals(SignalingTypes.HELLO, hello.type)
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.HELLO_ACK,
                payload = buildJsonObject { put("online", true) },
            ),
        )
        assertTrue(client.peerOnline.value)
        client.stop()
    }

    @Test
    fun `re-emits call messages on incoming`() = runTest {
        val connector = FakeConnector()
        val client = SignalingClient(connector, "https://w", "phone-8a", "phone-10pro", this)
        client.start()
        client.incoming.test {
            connector.serverSends(
                SignalingMessage(
                    type = SignalingTypes.CALL_OFFER,
                    from = "phone-10pro",
                    payload = buildJsonObject { put("sdp", "v=0") },
                ),
            )
            assertEquals(SignalingTypes.CALL_OFFER, awaitItem().type)
        }
        client.stop()
    }

    @Test
    fun `reconnects with backoff after the socket dies`() = runTest {
        val connector = FakeConnector()
        val client = SignalingClient(connector, "https://w", "phone-8a", "phone-10pro", this)
        client.start()
        assertEquals(1, connector.handles.size)
        connector.lastOnClosed!!()
        assertEquals(SignalingConnectionState.DISCONNECTED, client.connectionState.value)
        advanceTimeBy(1_100)
        assertEquals(2, connector.handles.size)
        connector.lastOnClosed!!()
        advanceTimeBy(2_100)
        assertEquals(3, connector.handles.size)
        client.stop()
    }

    @Test
    fun `stop closes the socket and stops reconnecting`() = runTest {
        val connector = FakeConnector()
        val client = SignalingClient(connector, "https://w", "phone-8a", "phone-10pro", this)
        client.start()
        client.stop()
        assertTrue(connector.handles.single().closed)
        advanceTimeBy(60_000)
        assertEquals(1, connector.handles.size)
    }
}
