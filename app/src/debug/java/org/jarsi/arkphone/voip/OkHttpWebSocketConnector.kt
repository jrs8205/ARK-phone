package org.jarsi.arkphone.voip

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Real connector: OkHttp WebSocket with the per-device bearer. */
class OkHttpWebSocketConnector(
    private val client: OkHttpClient,
) : WebSocketConnector {

    override fun connect(
        url: String,
        bearer: String,
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    ): WebSocketHandle {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $bearer")
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()
                override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                    onClosed(code, reason)
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    onClosed(FAILURE_CLOSE, t.message.orEmpty())
            },
        )
        return object : WebSocketHandle {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close() { socket.close(NORMAL_CLOSE, null) }
        }
    }

    private companion object {
        const val NORMAL_CLOSE = 1000
        // A transport failure is never a "superseded" close, so it must not
        // collide with the reason the client uses to suppress reconnects.
        const val FAILURE_CLOSE = 1006
    }
}
