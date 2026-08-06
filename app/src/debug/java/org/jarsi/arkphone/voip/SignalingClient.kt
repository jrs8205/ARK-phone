package org.jarsi.arkphone.voip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Boundary that hides OkHttp so the client logic is unit-testable. */
interface WebSocketConnector {
    /** Opens a socket; [onText] receives frames, [onClosed] fires once on any terminal close/failure. */
    fun connect(url: String, onText: (String) -> Unit, onClosed: () -> Unit): WebSocketHandle
}

interface WebSocketHandle {
    fun send(text: String): Boolean
    fun close()
}

enum class SignalingConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

class SignalingClient(
    private val connector: WebSocketConnector,
    private val workerUrl: String,
    private val deviceId: String,
    private val peerId: String,
    private val scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow(SignalingConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private val _peerOnline = MutableStateFlow(false)
    val peerOnline: StateFlow<Boolean> = _peerOnline.asStateFlow()

    private val _incoming = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 16)
    val incoming: SharedFlow<SignalingMessage> = _incoming.asSharedFlow()

    private var handle: WebSocketHandle? = null
    private var reconnectJob: Job? = null
    private var presenceJob: Job? = null
    private var reconnectDelayMs = 1_000L
    private var running = false

    fun start() {
        if (running) return
        running = true
        open()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        presenceJob?.cancel()
        handle?.close()
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
    }

    fun send(message: SignalingMessage) {
        handle?.send(SignalingJson.encode(message))
    }

    private fun open() {
        _connectionState.value = SignalingConnectionState.CONNECTING
        handle = connector.connect(
            url = "$workerUrl/connect/$deviceId",
            onText = ::onText,
            onClosed = ::onClosed,
        )
        _connectionState.value = SignalingConnectionState.CONNECTED
        reconnectDelayMs = 1_000L
        send(
            SignalingMessage(
                type = SignalingTypes.HELLO,
                payload = buildJsonObject { put("peer", peerId) },
            ),
        )
        presenceJob?.cancel()
        presenceJob = scope.launch {
            while (true) {
                delay(10_000)
                send(SignalingMessage(type = SignalingTypes.PRESENCE_QUERY, to = peerId))
            }
        }
    }

    private fun onText(text: String) {
        val message = SignalingJson.decode(text) ?: return
        when (message.type) {
            SignalingTypes.HELLO_ACK, SignalingTypes.PRESENCE -> {
                _peerOnline.value =
                    message.payload?.get("online")?.jsonPrimitive?.booleanOrNull ?: false
            }
            else -> _incoming.tryEmit(message)
        }
    }

    private fun onClosed() {
        if (!running) return
        presenceJob?.cancel()
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
        _peerOnline.value = false
        reconnectJob = scope.launch {
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(30_000L)
            if (running) open()
        }
    }
}
