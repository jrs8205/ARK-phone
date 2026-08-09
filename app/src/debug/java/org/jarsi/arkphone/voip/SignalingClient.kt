package org.jarsi.arkphone.voip

import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Boundary that hides OkHttp so the client logic is unit-testable. */
interface WebSocketConnector {
    /**
     * Opens a socket. [onOpen] fires on the successful handshake, [onText] on
     * every text frame, [onClosed] once with the close code and reason on any
     * terminal close or failure.
     */
    fun connect(
        url: String,
        bearer: String,
        onOpen: () -> Unit,
        onText: (String) -> Unit,
        onClosed: (code: Int, reason: String) -> Unit,
    ): WebSocketHandle
}

interface WebSocketHandle {
    fun send(text: String): Boolean
    fun close()
}

enum class SignalingConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * The device's inbox socket. One instance per process: it opens
 * `/connect/<own code>` with the per-device bearer and stays open while the
 * app has anything to do.
 */
class SignalingClient(
    private val connector: WebSocketConnector,
    private val workerUrl: String,
    private val code: String,
    private val deviceToken: String,
    private val scope: CoroutineScope,
) : CallSignaling {
    private val _connectionState = MutableStateFlow(SignalingConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SignalingConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    override val incoming: SharedFlow<SignalingMessage> = _incoming.asSharedFlow()

    private val pendingReach = mutableMapOf<String, CompletableDeferred<Boolean>>()

    // Frames refused by a dying socket, replayed on the next one: a lost
    // call-answer strands the caller, a lost call-end strands the peer. The
    // protocol tolerates the duplicates a retry can produce.
    private val resendQueue = ArrayDeque<SignalingMessage>()

    private var handle: WebSocketHandle? = null
    private var reconnectJob: Job? = null
    private var pingJob: Job? = null
    private var reconnectDelayMs = 1_000L
    private var running = false

    // Callbacks belong to the socket generation that registered them. A dying
    // socket's late close once cancelled its replacement's ping loop and
    // spawned a third connection — network switches turned into thrashing.
    private var generation = 0

    fun start() {
        if (running) return
        running = true
        open()
    }

    fun stop() {
        running = false
        generation++
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        handle?.close()
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
    }

    /**
     * Drops the current socket and dials a fresh one at once. The wake path
     * uses this: an FCM wake means the worker no longer trusts our socket,
     * and only a NEW connection flushes the queued offer — a cached
     * CONNECTED state must not be believed then.
     */
    fun forceReconnect() {
        if (!running) return
        pingJob?.cancel()
        reconnectJob?.cancel()
        handle?.close()
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
        open()
    }

    /** False when the frame could not be handed to a live socket. */
    override fun send(message: SignalingMessage): Boolean {
        val sent = handle?.send(SignalingJson.encode(message)) ?: false
        if (!sent) {
            stashForResend(message)
            onSendRefused()
        }
        return sent
    }

    private fun stashForResend(message: SignalingMessage) {
        if (message.type !in RESEND_TYPES) return
        synchronized(resendQueue) {
            resendQueue += message
            while (resendQueue.size > MAX_RESEND) resendQueue.removeFirst()
        }
    }

    /**
     * The routing pre-check. Returns true only on `online: true`, which is
     * terminal; a `waking` reply is not an answer, so the query keeps waiting
     * until [timeoutMs]. A reply for a peer with no pending query is ignored.
     */
    suspend fun reach(peer: String, timeoutMs: Long): Boolean {
        val pending = CompletableDeferred<Boolean>()
        synchronized(pendingReach) { pendingReach[peer] = pending }
        try {
            if (!send(SignalingMessage(type = SignalingTypes.REACH_QUERY, to = peer))) return false
            return withTimeoutOrNull(timeoutMs) { pending.await() } ?: false
        } finally {
            synchronized(pendingReach) { pendingReach.remove(peer) }
        }
    }

    private fun open() {
        val gen = ++generation
        _connectionState.value = SignalingConnectionState.CONNECTING
        handle = connector.connect(
            url = "$workerUrl/connect/$code",
            bearer = "$code.$deviceToken",
            onOpen = { ifCurrent(gen) { onOpen() } },
            onText = { text -> ifCurrent(gen) { onText(text) } },
            onClosed = { closeCode, reason -> ifCurrent(gen) { onClosed(closeCode, reason) } },
        )
    }

    private inline fun ifCurrent(gen: Int, block: () -> Unit) {
        if (gen == generation) block()
    }

    private fun onOpen() {
        reconnectDelayMs = 1_000L
        _connectionState.value = SignalingConnectionState.CONNECTED
        startPinging()
        flushResendQueue()
    }

    private fun flushResendQueue() {
        val stashed = synchronized(resendQueue) {
            val copy = resendQueue.toList()
            resendQueue.clear()
            copy
        }
        val current = handle ?: return
        for (message in stashed) current.send(SignalingJson.encode(message))
    }

    /**
     * The socket's proof of life: the worker counts a socket silent for 45 s
     * as a zombie and stops trusting it with calls, so pings must keep
     * flowing for as long as the socket claims to be open. A refused ping
     * doubles as the half-dead-socket probe.
     */
    private fun startPinging() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                val current = handle ?: return@launch
                if (!current.send(PING)) {
                    onSendRefused()
                    return@launch
                }
            }
        }
    }

    private fun onText(text: String) {
        // The keepalive answer is the bare string "pong", not JSON.
        if (text == PONG) return
        val message = SignalingJson.decode(text) ?: return
        if (message.type == SignalingTypes.REACH_REPLY) {
            onReachReply(message)
            return
        }
        _incoming.tryEmit(message)
    }

    private fun onReachReply(message: SignalingMessage) {
        val peer = message.from ?: return
        val online = message.payload?.get("online")?.jsonPrimitive?.booleanOrNull ?: return
        if (!online) return
        synchronized(pendingReach) { pendingReach[peer] }?.complete(true)
    }

    private fun onClosed(closeCode: Int, reason: String) {
        pingJob?.cancel()
        // A 1000/"superseded" close means this device opened a newer socket.
        // Reconnecting here would supersede the socket that just replaced this
        // one, and the loop would repeat forever.
        if (closeCode == NORMAL_CLOSE && reason == SUPERSEDED_REASON) return
        if (!running) return
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
        scheduleReconnect()
    }

    /** A send into a half-dead socket is silently lost, so drop and reopen. */
    private fun onSendRefused() {
        if (!running) return
        val lost = handle ?: return
        pingJob?.cancel()
        lost.close()
        handle = null
        _connectionState.value = SignalingConnectionState.DISCONNECTED
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val delayMs = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        reconnectJob = scope.launch {
            delay(delayMs)
            if (running) open()
        }
    }

    companion object {
        const val SUPERSEDED_REASON = "superseded"
        const val PING_INTERVAL_MS = 20_000L
        private const val NORMAL_CLOSE = 1000
        private const val PING = "ping"
        private const val PONG = "pong"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val MAX_RESEND = 32
        private val RESEND_TYPES = setOf(
            SignalingTypes.CALL_OFFER,
            SignalingTypes.CALL_ANSWER,
            SignalingTypes.CALL_REJECT,
            SignalingTypes.ICE_CANDIDATE,
            SignalingTypes.CALL_END,
        )
    }
}
