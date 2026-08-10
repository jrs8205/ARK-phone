package org.jarsi.arkphone.voip

import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalingClientTest {

    private class FakeHandle(var accepts: Boolean = true) : WebSocketHandle {
        val sent = mutableListOf<String>()
        var closed = false
        var onSend: ((String) -> Boolean)? = null
        override fun send(text: String): Boolean {
            onSend?.let { return it(text) }
            if (!accepts) return false
            sent.add(text)
            return true
        }
        override fun close() { closed = true }
    }

    private class FakeConnector : WebSocketConnector {
        val handles = mutableListOf<FakeHandle>()
        val urls = mutableListOf<String>()
        val bearers = mutableListOf<String>()
        var lastOnOpen: (() -> Unit)? = null
        var lastOnText: ((String) -> Unit)? = null
        var lastOnClosed: ((Int, String) -> Unit)? = null

        override fun connect(
            url: String,
            bearer: String,
            onOpen: () -> Unit,
            onText: (String) -> Unit,
            onClosed: (Int, String) -> Unit,
        ): WebSocketHandle {
            urls += url
            bearers += bearer
            lastOnOpen = onOpen
            lastOnText = onText
            lastOnClosed = onClosed
            return FakeHandle().also { handles.add(it) }
        }

        fun opens() = lastOnOpen!!()
        fun serverSends(message: SignalingMessage) = lastOnText!!(SignalingJson.encode(message))
        fun serverSendsRaw(text: String) = lastOnText!!(text)
    }

    private fun client(connector: FakeConnector, scope: CoroutineScope) =
        SignalingClient(
            connector = connector,
            workerUrl = "https://w",
            code = "ARK-AAAA-AAAA",
            deviceToken = "token-abc",
            scope = scope,
        )

    @Test
    fun `it opens its own inbox with a code dot token bearer`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        assertEquals("https://w/connect/ARK-AAAA-AAAA", connector.urls.single())
        assertEquals("ARK-AAAA-AAAA.token-abc", connector.bearers.single())
        client.stop()
    }

    @Test
    fun `the connection is only reported connected after the handshake`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        assertEquals(SignalingConnectionState.CONNECTING, client.connectionState.value)
        connector.opens()
        assertEquals(SignalingConnectionState.CONNECTED, client.connectionState.value)
        client.stop()
    }

    @Test
    fun `a superseded close is not an error and never reconnects`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.lastOnClosed!!(1000, SignalingClient.SUPERSEDED_REASON)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, connector.handles.size)
        client.stop()
    }

    @Test
    fun `any other close reconnects with backoff`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.lastOnClosed!!(1006, "")
        assertEquals(SignalingConnectionState.DISCONNECTED, client.connectionState.value)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, connector.handles.size)
        connector.lastOnClosed!!(1006, "")
        advanceTimeBy(2_100)
        runCurrent()
        assertEquals(3, connector.handles.size)
        client.stop()
    }

    @Test
    fun `the bare pong keepalive never reaches the decoder`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        client.incoming.test {
            connector.serverSendsRaw("pong")
            connector.serverSends(
                SignalingMessage(
                    type = SignalingTypes.CALL_OFFER,
                    from = "ARK-BBBB-BBBB",
                    payload = buildJsonObject { put("sdp", "v=0") },
                ),
            )
            assertEquals(SignalingTypes.CALL_OFFER, awaitItem().type)
        }
        client.stop()
    }

    @Test
    fun `reach returns true when the peer answers online`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        val result = async { client.reach("ARK-BBBB-BBBB", 4_000) }
        runCurrent()
        val query = SignalingJson.decode(connector.handles.single().sent.single())!!
        assertEquals(SignalingTypes.REACH_QUERY, query.type)
        assertEquals("ARK-BBBB-BBBB", query.to)
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject { put("online", true) },
            ),
        )
        assertTrue(result.await())
        client.stop()
    }

    @Test
    fun `a waking reply alone is not reachable and the query times out`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        val result = async { client.reach("ARK-BBBB-BBBB", 4_000) }
        runCurrent()
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject {
                    put("online", false)
                    put("waking", true)
                },
            ),
        )
        advanceTimeBy(4_100)
        runCurrent()
        assertFalse(result.await())
        client.stop()
    }

    @Test
    fun `a late online reply for a waking peer still counts`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        val result = async { client.reach("ARK-BBBB-BBBB", 4_000) }
        runCurrent()
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject {
                    put("online", false)
                    put("waking", true)
                },
            ),
        )
        advanceTimeBy(1_000)
        connector.serverSends(
            SignalingMessage(
                type = SignalingTypes.REACH_REPLY,
                from = "ARK-BBBB-BBBB",
                payload = buildJsonObject { put("online", true) },
            ),
        )
        assertTrue(result.await())
        client.stop()
    }

    @Test
    fun `a reach reply with no pending query is ignored, not emitted`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        client.incoming.test {
            connector.serverSends(
                SignalingMessage(
                    type = SignalingTypes.REACH_REPLY,
                    from = "ARK-CCCC-CCCC",
                    payload = buildJsonObject { put("online", true) },
                ),
            )
            connector.serverSends(
                SignalingMessage(type = SignalingTypes.CALL_END, from = "ARK-BBBB-BBBB"),
            )
            assertEquals(SignalingTypes.CALL_END, awaitItem().type)
        }
        client.stop()
    }

    @Test
    fun `a refused send drops the socket and reconnects`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.handles.single().accepts = false
        assertFalse(
            client.send(SignalingMessage(type = SignalingTypes.CALL_END, to = "ARK-BBBB-BBBB")),
        )
        assertEquals(SignalingConnectionState.DISCONNECTED, client.connectionState.value)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, connector.handles.size)
        client.stop()
    }

    @Test
    fun `the socket is kept alive with pings while open`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        advanceTimeBy(SignalingClient.PING_INTERVAL_MS + 100)
        runCurrent()
        assertEquals(listOf("ping"), connector.handles.single().sent)
        advanceTimeBy(SignalingClient.PING_INTERVAL_MS)
        runCurrent()
        assertEquals(listOf("ping", "ping"), connector.handles.single().sent)
        client.stop()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, connector.handles.single().sent.size)
    }

    @Test
    fun `a refused ping drops the socket and reconnects`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.handles.single().accepts = false
        advanceTimeBy(SignalingClient.PING_INTERVAL_MS + 100)
        runCurrent()
        assertEquals(SignalingConnectionState.DISCONNECTED, client.connectionState.value)
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(2, connector.handles.size)
        client.stop()
    }

    @Test
    fun `a stale socket's late close cannot disturb its replacement`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        val staleClose = connector.lastOnClosed!!
        staleClose(1006, "")
        advanceTimeBy(1_100)
        runCurrent()
        connector.opens()
        assertEquals(SignalingConnectionState.CONNECTED, client.connectionState.value)
        // The dead socket's second, delayed callback arrives after the
        // replacement opened; it must be ignored, not tear the new socket down.
        staleClose(1006, "late")
        runCurrent()
        assertEquals(SignalingConnectionState.CONNECTED, client.connectionState.value)
        assertEquals(2, connector.handles.size)
        advanceTimeBy(SignalingClient.PING_INTERVAL_MS + 100)
        runCurrent()
        assertEquals(listOf("ping"), connector.handles[1].sent)
        client.stop()
    }

    @Test
    fun `a refused frame is resent on the next socket`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.handles.single().accepts = false
        assertFalse(
            client.send(SignalingMessage(type = SignalingTypes.CALL_END, to = "ARK-BBBB-BBBB")),
        )
        advanceTimeBy(1_100)
        runCurrent()
        connector.opens()
        val resent = connector.handles[1].sent.mapNotNull { SignalingJson.decode(it) }
        assertEquals(listOf(SignalingTypes.CALL_END), resent.map { it.type })
        assertEquals("ARK-BBBB-BBBB", resent.single().to)
        client.stop()
    }

    @Test
    fun `a frame refused during the flush survives to the socket after it`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.handles.single().accepts = false
        assertFalse(
            client.send(SignalingMessage(type = SignalingTypes.CALL_END, to = "ARK-BBBB-BBBB")),
        )
        advanceTimeBy(1_100)
        runCurrent()
        // The replacement socket refuses its flush too — the frame must go
        // back in line instead of vanishing with the cleared queue.
        connector.handles[1].accepts = false
        connector.opens()
        advanceTimeBy(1_100)
        runCurrent()
        connector.opens()
        val resent = connector.handles[2].sent.mapNotNull { SignalingJson.decode(it) }
        assertEquals(listOf(SignalingTypes.CALL_END), resent.map { it.type })
        assertEquals("ARK-BBBB-BBBB", resent.single().to)
        client.stop()
    }

    @Test
    fun `the resend cap drops candidates before call frames when a flush is refused`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        connector.handles.single().accepts = false
        // One critical frame, then enough candidates to fill the cap of 32.
        client.send(SignalingMessage(type = SignalingTypes.CALL_ANSWER, to = "ARK-BBBB-BBBB"))
        repeat(31) { i ->
            client.send(
                SignalingMessage(
                    type = SignalingTypes.ICE_CANDIDATE,
                    to = "ARK-BBBB-BBBB",
                    payload = buildJsonObject { put("candidate", "c$i") },
                ),
            )
        }
        advanceTimeBy(1_100)
        runCurrent()
        // The replacement refuses its flush too, and one more candidate is
        // stashed mid-flush: the requeued tail plus the newcomer overflow
        // the cap by one, and the evicted frame must be a candidate — the
        // answer is the frame the whole resend queue exists for.
        val replacement = connector.handles[1]
        var injected = false
        replacement.onSend = {
            if (!injected) {
                injected = true
                client.send(
                    SignalingMessage(
                        type = SignalingTypes.ICE_CANDIDATE,
                        to = "ARK-BBBB-BBBB",
                        payload = buildJsonObject { put("candidate", "mid-flush") },
                    ),
                )
            }
            false
        }
        connector.opens()
        advanceTimeBy(2_100)
        runCurrent()
        connector.opens()
        val resent = connector.handles[2].sent.mapNotNull { SignalingJson.decode(it) }
        assertTrue(resent.any { it.type == SignalingTypes.CALL_ANSWER })
        assertEquals(32, resent.size)
        client.stop()
    }

    @Test
    fun `forceReconnect dials a fresh socket immediately`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        client.forceReconnect()
        runCurrent()
        assertEquals(2, connector.handles.size)
        connector.opens()
        assertEquals(SignalingConnectionState.CONNECTED, client.connectionState.value)
        client.stop()
    }

    @Test
    fun `stop closes the socket and stops reconnecting`() = runTest {
        val connector = FakeConnector()
        val client = client(connector, backgroundScope)
        client.start()
        connector.opens()
        client.stop()
        assertTrue(connector.handles.single().closed)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, connector.handles.size)
    }
}
