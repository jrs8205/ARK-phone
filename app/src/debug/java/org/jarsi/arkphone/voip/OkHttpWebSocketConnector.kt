package org.jarsi.arkphone.voip

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Real connector: OkHttp WebSocket with the spike's bearer token. */
class OkHttpWebSocketConnector(
    private val client: OkHttpClient,
    private val authToken: String,
) : WebSocketConnector {

    override fun connect(
        url: String,
        onText: (String) -> Unit,
        onClosed: () -> Unit,
    ): WebSocketHandle {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $authToken")
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClosed()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                    onClosed()
            },
        )
        return object : WebSocketHandle {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close() { socket.close(1000, null) }
        }
    }
}
