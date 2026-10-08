package org.jarsi.arkphone.voip

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OkHttpWebSocketConnectorTest {

    @Test
    fun aBearerThatCannotBeAHeaderReportsAFailureInsteadOfThrowing() {
        // A device token with a control character used to escape as an
        // IllegalArgumentException from the request builder and take the
        // whole startup coroutine with it, at every launch.
        val client = OkHttpClient()
        val closed = CountDownLatch(1)
        var closeCode = 0
        val handle = OkHttpWebSocketConnector(client).connect(
            url = "wss://127.0.0.1:1/connect/ARK-E5HU-JVA8",
            bearer = "ARK-E5HU-JVA8.token\ncontrol",
            onOpen = {},
            onText = {},
            onClosed = { code, _ ->
                closeCode = code
                closed.countDown()
            },
        )

        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(1006, closeCode)
        assertFalse(handle.send("ping"))
        handle.close()
        client.dispatcher.executorService.shutdown()
    }
}
