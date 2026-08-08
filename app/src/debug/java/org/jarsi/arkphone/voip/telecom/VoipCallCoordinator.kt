package org.jarsi.arkphone.voip.telecom

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.IncomingArkCall
import org.jarsi.arkphone.voip.VoipCallGateway
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.VoipMediaSession
import org.jarsi.arkphone.voip.VoipMediaSessionFactory

/** The reach-query budget before the call goes out over the carrier instead. */
const val VOIP_REACH_TIMEOUT_MS: Long = 4_000L

/** How long a VoIP attempt may sit unanswered before the carrier takes over. */
const val VOIP_CONNECT_TIMEOUT_MS: Long = 15_000L

/**
 * Owns the one VoIP call this phone can have at a time and drives every
 * surface it touches. The rule the whole design serves: any uncertainty on
 * this path degrades to a carrier call, never to silence.
 */
class VoipCallCoordinator(
    private val reachCheck: suspend (code: String, timeoutMs: Long) -> Boolean,
    private val sessionFactory: VoipMediaSessionFactory,
    private val telecom: VoipTelecom,
    private val ui: VoipCallUi,
    private val nicknameForCode: (String) -> String?,
    private val numberForCode: (String) -> String?,
    private val callLog: ArkCallLog,
    private val missedCalls: (ArkCallRecord) -> Unit,
    private val clock: Clock,
    private val scope: CoroutineScope,
) : VoipCallGateway {

    private var active: ActiveCall? = null

    private class ActiveCall(
        val handle: VoipCallHandle,
        val direction: VoipCallDirection,
        val session: VoipMediaSession,
        val sessionScope: CoroutineScope,
        val onFallbackToCarrier: (() -> Unit)?,
        var stateJob: Job? = null,
        var timeoutJob: Job? = null,
        var answered: Boolean = false,
    )

    /** Each call gets a child scope so a dead session cannot keep collecting. */
    private fun newSessionScope() = CoroutineScope(scope.coroutineContext + Job())

    override fun startCall(link: ArkLink, onFallbackToCarrier: () -> Unit): Boolean {
        Log.i(TAG, "ARK startCall to=${link.code} active=${active != null}")
        if (active != null) return false
        val id = "voip-out-${link.code}"
        val sessionScope = newSessionScope()
        val session = sessionFactory.create(link.code, null, sessionScope)
        val handle = VoipCallHandle(
            id = id,
            number = link.number,
            displayName = link.nickname,
            direction = VoipCallDirection.OUTGOING,
            actions = actionsFor(session),
            clock = clock,
        )
        if (!telecom.add(handle, onSystemAnswer = { session.answer() }, onSystemDisconnect = { session.hangUp() })) {
            Log.i(TAG, "ARK startCall telecom refused")
            sessionScope.cancel()
            return false
        }
        val call =
            ActiveCall(handle, VoipCallDirection.OUTGOING, session, sessionScope, onFallbackToCarrier)
        active = call
        ui.added(handle)
        ui.openCallScreen()
        observe(call)
        scope.launch {
            // The screen is already up; the pre-check runs behind it.
            val reachable = runCatching { reachCheck(link.code, VOIP_REACH_TIMEOUT_MS) }
                .getOrDefault(false)
            if (active !== call) return@launch
            if (!reachable) {
                fallBack(call)
                return@launch
            }
            session.placeCall()
            armConnectTimeout(call)
        }
        return true
    }

    /** A call reconciled out of the inbox flush. */
    fun onIncoming(call: IncomingArkCall) {
        Log.i(TAG, "ARK onIncoming from=${call.fromCode} active=${active != null}")
        if (active != null) return
        val id = "voip-in-${call.fromCode}"
        val sessionScope = newSessionScope()
        val session = sessionFactory.create(call.fromCode, call.offerSdp, sessionScope)
        val handle = VoipCallHandle(
            id = id,
            number = numberForCode(call.fromCode),
            displayName = nicknameForCode(call.fromCode),
            direction = VoipCallDirection.INCOMING,
            actions = actionsFor(session),
            clock = clock,
        )
        if (!telecom.add(handle, onSystemAnswer = { session.answer() }, onSystemDisconnect = { session.hangUp() })) {
            Log.i(TAG, "ARK onIncoming telecom refused")
            sessionScope.cancel()
            return
        }
        val activeCall = ActiveCall(
            handle,
            VoipCallDirection.INCOMING,
            session,
            sessionScope,
            onFallbackToCarrier = null,
        )
        active = activeCall
        ui.added(handle)
        ui.showIncoming(handle)
        // Same surface as a ringing carrier call: the notification alone is
        // easy to miss, so the call screen opens with it (field feedback).
        ui.openCallScreen()
        Log.i(TAG, "ARK incoming ringing id=$id")
        observe(activeCall)
        armConnectTimeout(activeCall)
    }

    private fun actionsFor(session: VoipMediaSession) = object : VoipCallActions {
        override fun answer() = session.answer()
        override fun reject() = session.reject()
        override fun hangUp() = session.hangUp()
    }

    private fun observe(call: ActiveCall) {
        call.stateJob = scope.launch {
            call.session.state.collect { state ->
                call.handle.onState(state)
                ui.changed()
                when (state) {
                    VoipCallState.InCall -> {
                        call.answered = true
                        call.timeoutJob?.cancel()
                        telecom.setActive(call.handle.id)
                        ui.startCallService()
                        ui.showOngoing(call.handle)
                    }
                    is VoipCallState.Ended -> finish(call)
                    else -> Unit
                }
            }
        }
    }

    private fun armConnectTimeout(call: ActiveCall) {
        call.timeoutJob?.cancel()
        call.timeoutJob = scope.launch {
            delay(VOIP_CONNECT_TIMEOUT_MS)
            if (active !== call || call.answered) return@launch
            call.session.hangUp()
            fallBack(call)
        }
    }

    /** Tears the VoIP attempt down and hands the call to the carrier. */
    private fun fallBack(call: ActiveCall) {
        val carrier = call.onFallbackToCarrier
        finish(call)
        carrier?.invoke()
    }

    private fun finish(call: ActiveCall) {
        if (active !== call) return
        active = null
        call.timeoutJob?.cancel()
        call.stateJob?.cancel()
        call.sessionScope.cancel()
        telecom.remove(call.handle.id)
        ui.clearNotification()
        ui.stopCallService()
        ui.removed(call.handle.id)
        val ended = call.handle.state as? VoipCallState.Ended ?: return
        // A carrier fallback is one call from the user's point of view; only a
        // VoIP attempt that actually reached the peer leaves a row.
        val record = arkCallRecordOf(
            handle = call.handle,
            direction = call.direction,
            endReason = ended.reason,
            endedAtMillis = clock.nowMillis(),
        )
        callLog.record(record)
        if (record.type == ArkCallType.MISSED) missedCalls(record)
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
