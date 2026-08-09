package org.jarsi.arkphone.voip

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/** How long the buffered flush is collected before anything is allowed to ring. */
const val FLUSH_DRAIN_MS: Long = 500L

/** How long connecting may take before the inbox is treated as unreachable. */
private const val CONNECT_TIMEOUT_MS = 8_000L

/** Post-drain slack for the ring to reach Telecom before the wake lock drops. */
private const val WAKE_RING_MARGIN_MS = 500L

/**
 * The one long-lived piece of VoIP state in the process: this device's inbox
 * socket, the flush reconciliation that decides what rings, and the reach
 * pre-check the routing branch uses.
 */
@Singleton
class VoipEngine @Inject constructor(
    private val identityRepository: ArkIdentityRepository,
    private val connector: WebSocketConnector,
    private val config: VoipConfig,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val connectMutex = Mutex()

    private var client: SignalingClient? = null

    private var draining = false
    private val drained = mutableListOf<SignalingMessage>()

    private val _incomingCalls = MutableSharedFlow<IncomingArkCall>(extraBufferCapacity = 8)
    val incomingCalls: SharedFlow<IncomingArkCall> = _incomingCalls.asSharedFlow()

    // Replayed with sequence stamps: a ringing session is created a few
    // dispatches AFTER its offer was emitted, and without replay every frame
    // in that gap (candidates, even the call-end that cancels the ring) was
    // gone before the session could subscribe. The stamp lets each session
    // skip everything older than its own call.
    private val _signals = MutableSharedFlow<StampedSignal>(replay = 64, extraBufferCapacity = 64)
    val signals: SharedFlow<StampedSignal> = _signals.asSharedFlow()
    private var signalSeq = 0L

    fun currentSignalSeq(): Long = signalSeq

    /**
     * The FCM wake path. Returns only once the inbox is connected and the
     * flush drain has had time to ring, so the messaging service can hold its
     * process priority and wake lock over the whole window — a wake that
     * merely launched a detached coroutine used to die with the service on a
     * dozing phone.
     */
    suspend fun awaitWake() {
        // A wake means the worker no longer trusts our socket, and only a NEW
        // connection flushes the queued offer — a half-open socket still
        // cached as CONNECTED would strand the call.
        client?.forceReconnect()
        if (connect()) delay(FLUSH_DRAIN_MS + WAKE_RING_MARGIN_MS)
    }

    /** True once the inbox socket is open. False when this device has no identity. */
    suspend fun connect(): Boolean {
        // A checkout without arkphone.voip.workerUrl must be a silent no-op,
        // not a crash inside OkHttp's URL parser.
        if (config.workerUrl.isBlank()) return false
        val active = connectMutex.withLock {
            val identity = identityRepository.identity.first() ?: return false
            client ?: SignalingClient(
                connector = connector,
                workerUrl = config.workerUrl,
                code = identity.code,
                deviceToken = identity.deviceToken,
                scope = scope,
            ).also { created ->
                client = created
                startCollecting(created)
                created.start()
            }
        }
        return withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            active.connectionState.first { it == SignalingConnectionState.CONNECTED }
            true
        } ?: false
    }

    /**
     * The routing pre-check; false whenever anything at all is uncertain.
     * [timeoutMs] caps the WHOLE check: a cold own-socket connect must eat
     * into the budget, not stack its 8 s in front of it — the promise to the
     * user is one bounded wait before the carrier takes over.
     */
    suspend fun reach(peerCode: String, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            if (!connect()) return@withTimeoutOrNull false
            val active = client ?: return@withTimeoutOrNull false
            active.reach(peerCode, timeoutMs)
        } ?: false

    fun send(message: SignalingMessage): Boolean = client?.send(message) ?: false

    private fun startCollecting(created: SignalingClient) {
        scope.launch {
            created.connectionState.collect { state ->
                if (state == SignalingConnectionState.CONNECTED) beginDrain()
            }
        }
        scope.launch {
            created.incoming.collect { message ->
                if (draining) drained += message else dispatch(message)
            }
        }
    }

    private fun beginDrain() {
        if (draining) return
        draining = true
        drained.clear()
        scope.launch {
            delay(FLUSH_DRAIN_MS)
            val batch = drained.toList()
            drained.clear()
            draining = false
            batch.forEach { _signals.tryEmit(StampedSignal(++signalSeq, it)) }
            // The in-batch candidates ride in iceCandidates; the replay filter
            // starts right after the batch, so nothing arrives twice.
            val call = reconcileFlush(batch)?.copy(sinceSeq = signalSeq)
            if (batch.isNotEmpty() || call != null) {
                Log.i(TAG, "ARK flush drained=${batch.size} ring=${call?.fromCode}")
            }
            call?.let { ring(it) }
        }
    }

    private fun dispatch(message: SignalingMessage) {
        val seq = ++signalSeq
        _signals.tryEmit(StampedSignal(seq, message))
        // sinceSeq is the offer's own stamp: the session seeds the offer via
        // the ring, and replay hands it every frame that came after.
        reconcileFlush(listOf(message))?.let { ring(it.copy(sinceSeq = seq)) }
    }

    private fun ring(call: IncomingArkCall) {
        val emitted = _incomingCalls.tryEmit(call)
        Log.i(TAG, "ARK ring from=${call.fromCode} emitted=$emitted")
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
