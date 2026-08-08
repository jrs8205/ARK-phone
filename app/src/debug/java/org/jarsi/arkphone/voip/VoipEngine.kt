package org.jarsi.arkphone.voip

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

    private val _signals = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val signals: SharedFlow<SignalingMessage> = _signals.asSharedFlow()

    /** Called from the FCM wake path; connecting is all a wake ever does. */
    fun onWake() {
        scope.launch { connect() }
    }

    /** True once the inbox socket is open. False when this device has no identity. */
    suspend fun connect(): Boolean {
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

    /** The routing pre-check; false whenever anything at all is uncertain. */
    suspend fun reach(peerCode: String, timeoutMs: Long): Boolean {
        if (!connect()) return false
        val active = client ?: return false
        return active.reach(peerCode, timeoutMs)
    }

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
            batch.forEach { _signals.tryEmit(it) }
            reconcileFlush(batch)?.let { _incomingCalls.tryEmit(it) }
        }
    }

    private fun dispatch(message: SignalingMessage) {
        _signals.tryEmit(message)
        reconcileFlush(listOf(message))?.let { _incomingCalls.tryEmit(it) }
    }
}
