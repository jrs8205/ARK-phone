package org.jarsi.arkphone.voip.telecom

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jarsi.arkphone.telecom.DisconnectError
import org.jarsi.arkphone.telecom.InCallAudioController
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.IncomingArkCall
import org.jarsi.arkphone.voip.VoipCallGateway
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.VoipMediaSession
import org.jarsi.arkphone.voip.VoipMediaSessionFactory

/**
 * The reach-query budget before the call goes out over the carrier instead.
 * Field-measured: an FCM wake, cold process start and socket handshake take
 * 3–4 s on a locked phone, so 4 s lost the race by a hair; 7 s covers it and
 * still beats a carrier call's own setup time. The worker's reach watcher
 * (8 s) must outlive this.
 */
const val VOIP_REACH_TIMEOUT_MS: Long = 7_000L

/** How long a VoIP attempt may sit unanswered before the carrier takes over. */
const val VOIP_CONNECT_TIMEOUT_MS: Long = 15_000L

/** End reasons that are a deliberate outcome, not a failure to route around. */
private val DELIBERATE_END_REASONS =
    setOf("local-hangup", "local-reject", "rejected", "peer-hangup")

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
    private val blockCheck: suspend (number: String?) -> Boolean = { false },
    private val hasMicPermission: () -> Boolean = { true },
    private val disconnectErrorFor: (reason: String) -> DisconnectError? = { null },
    private val awaitLinkCache: suspend () -> Unit = {},
    private val arkCallsEnabled: () -> Boolean = { true },
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
        var answeredByUser: Boolean = false,
    )

    /**
     * The one funnel for every answer/reject/hang-up surface (call screen,
     * notification, Bluetooth): answering must also reach Telecom, and an
     * answer press must survive into the call-log row even when the media
     * never connects.
     */
    private inner class SessionActions(private val session: VoipMediaSession) : VoipCallActions {
        var call: ActiveCall? = null
        override fun answer() {
            call?.let { current ->
                Log.i(TAG, "ARK user answer id=${current.handle.id}")
                current.answeredByUser = true
                telecom.answered(current.handle.id)
                // The insistent ringtone must die with the answer, not wait
                // for the media to connect — an ICE failure used to leave it
                // looping over a call the user had already picked up.
                ui.silenceRinging(current.handle)
            }
            session.answer()
        }
        override fun reject() = session.reject()
        override fun hangUp() = session.hangUp()
    }

    /** Each call gets a child scope so a dead session cannot keep collecting. */
    private fun newSessionScope() = CoroutineScope(scope.coroutineContext + Job())

    override fun startCall(link: ArkLink, onFallbackToCarrier: () -> Unit): Boolean {
        Log.i(TAG, "ARK startCall to=${link.code} active=${active != null}")
        if (active != null) return false
        // Without the mic there is no usable internet call to offer; WebRTC
        // would open a silent track and Android 14+ kills the mic-typed
        // foreground service outright.
        if (!hasMicPermission()) {
            Log.i(TAG, "ARK startCall refused: no mic permission")
            return false
        }
        val id = "voip-out-${link.code}"
        val sessionScope = newSessionScope()
        val session = sessionFactory.create(link.code, null, emptyList(), -1L, null, sessionScope)
        val actions = SessionActions(session)
        val handle = VoipCallHandle(
            id = id,
            number = link.number,
            displayName = link.nickname,
            direction = VoipCallDirection.OUTGOING,
            actions = actions,
            clock = clock,
            disconnectErrorFor = disconnectErrorFor,
        )
        val call =
            ActiveCall(handle, VoipCallDirection.OUTGOING, session, sessionScope, onFallbackToCarrier)
        actions.call = call
        // Installed BEFORE Telecom: addCall runs on an immediate dispatcher,
        // so an inline refusal can reach onTelecomFailed before this method
        // resumes — the callback must find the call it is failing.
        active = call
        if (!addToTelecom(call)) {
            Log.i(TAG, "ARK startCall telecom refused")
            active = null
            sessionScope.cancel()
            return false
        }
        // An inline refusal (addCall failing before its first suspension on
        // Main.immediate) has already finished this call and started the
        // carrier fallback; opening surfaces for the dead handle would leave
        // a call nothing can ever remove.
        if (active !== call) return true
        ui.added(handle)
        attachAudio(call)
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

    /**
     * A call reconciled out of the inbox flush. Every dropped offer here is
     * safe by fallback: the caller's connect timeout hands the same call to
     * the carrier, so this phone still rings — just not over ARK.
     */
    fun onIncoming(call: IncomingArkCall) {
        Log.i(TAG, "ARK onIncoming from=${call.fromCode} active=${active != null}")
        if (active != null) return
        scope.launch {
            // A cold-start flush can beat Room's first link emission, and an
            // unlinked verdict off an empty cache would drop a legitimate
            // call the worker has already dequeued.
            awaitLinkCache()
            if (!arkCallsEnabled()) {
                Log.i(TAG, "ARK onIncoming dropped: internet calls disabled")
                return@launch
            }
            // Without the mic, answering would start a mic-typed foreground
            // service Android 14+ kills with a SecurityException.
            if (!hasMicPermission()) {
                Log.i(TAG, "ARK onIncoming dropped: no mic permission")
                return@launch
            }
            val number = numberForCode(call.fromCode)
            val nickname = nicknameForCode(call.fromCode)
            if (number == null && nickname == null) {
                // Knowing the code is not an invitation: without a local link
                // this account has no business ringing this phone.
                Log.i(TAG, "ARK onIncoming dropped: no local link")
                return@launch
            }
            // The same rules a carrier call answers to; an evaluator fault
            // must not silence a linked caller.
            val blocked = runCatching { blockCheck(number) }.getOrDefault(false)
            if (blocked) {
                Log.i(TAG, "ARK onIncoming dropped: blocked")
                return@launch
            }
            if (active != null) return@launch
            ring(call, number, nickname)
        }
    }

    private fun ring(call: IncomingArkCall, number: String?, nickname: String?) {
        val id = "voip-in-${call.fromCode}"
        val sessionScope = newSessionScope()
        val session = sessionFactory.create(
            call.fromCode,
            call.offerSdp,
            call.iceCandidates,
            call.sinceSeq,
            call.callId,
            sessionScope,
        )
        val actions = SessionActions(session)
        val handle = VoipCallHandle(
            id = id,
            number = number,
            displayName = nickname,
            direction = VoipCallDirection.INCOMING,
            actions = actions,
            clock = clock,
            disconnectErrorFor = disconnectErrorFor,
        )
        val activeCall = ActiveCall(
            handle,
            VoipCallDirection.INCOMING,
            session,
            sessionScope,
            onFallbackToCarrier = null,
        )
        actions.call = activeCall
        active = activeCall
        if (!addToTelecom(activeCall)) {
            Log.i(TAG, "ARK onIncoming telecom refused")
            active = null
            sessionScope.cancel()
            return
        }
        ui.added(handle)
        attachAudio(activeCall)
        ui.showIncoming(handle)
        // Same surface as a ringing carrier call: the notification alone is
        // easy to miss, so the call screen opens with it (field feedback).
        ui.openCallScreen()
        Log.i(TAG, "ARK incoming ringing id=$id")
        observe(activeCall)
        armConnectTimeout(activeCall)
    }

    /**
     * Mute acts on the WebRTC track, the route on Telecom; the reflected UI
     * state is optimistic — good enough until endpoints are observed live.
     */
    private fun attachAudio(call: ActiveCall) {
        var muted = false
        var speaker = false
        ui.attachAudioControls(object : InCallAudioController {
            override fun applyMuted(m: Boolean) {
                muted = m
                call.session.setMicEnabled(!m)
                ui.audioStateChanged(muted, speaker)
            }
            override fun applyRoute(s: Boolean) {
                speaker = s
                telecom.requestSpeaker(call.handle.id, s)
                ui.audioStateChanged(muted, speaker)
            }
        })
    }

    private fun addToTelecom(call: ActiveCall): Boolean = telecom.add(
        call.handle,
        onSystemAnswer = {
            call.answeredByUser = true
            ui.silenceRinging(call.handle)
            call.session.answer()
        },
        onSystemDisconnect = { call.session.hangUp() },
        onFailed = { onTelecomFailed(call) },
    )

    /** The platform withdrew a call it first accepted (async addCall refusal). */
    private fun onTelecomFailed(call: ActiveCall) {
        if (active !== call) return
        Log.i(TAG, "ARK telecom failed id=${call.handle.id}")
        if (call.direction == VoipCallDirection.OUTGOING && !call.answered) {
            fallBack(call)
        } else {
            call.session.hangUp()
        }
    }

    private fun observe(call: ActiveCall) {
        call.stateJob = scope.launch {
            call.session.state.collect { state ->
                call.handle.onState(state)
                ui.changed()
                when (state) {
                    VoipCallState.Connecting ->
                        // The mic track exists from here on, so the foreground
                        // service must cover ICE negotiation too — a phone
                        // locked mid-negotiation used to lose the mic before
                        // the call ever connected.
                        ui.startCallService()
                    VoipCallState.InCall -> {
                        call.answered = true
                        call.timeoutJob?.cancel()
                        telecom.setActive(call.handle.id)
                        ui.startCallService()
                        ui.showOngoing(call.handle)
                    }
                    is VoipCallState.Ended ->
                        if (shouldFallBack(call, state.reason)) fallBack(call) else finish(call)
                    else -> Unit
                }
            }
        }
    }

    /**
     * An outgoing attempt that died before anyone answered, for any reason
     * that was not a deliberate choice, still owes the user a phone call.
     */
    private fun shouldFallBack(call: ActiveCall, reason: String): Boolean =
        call.onFallbackToCarrier != null &&
            !call.answered &&
            reason !in DELIBERATE_END_REASONS

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
        // The session must die BEFORE the carrier dials: cancelling the scope
        // alone closes nothing, so the peer would keep ringing an attempt the
        // adapter of which stays live under the carrier call.
        call.session.hangUp()
        // A carrier fallback is one call from the user's point of view — the
        // carrier call makes the only history row. Dialing waits for Telecom
        // to release the ARK call, or the platform rejects the fallback as a
        // second concurrent call.
        finish(call, recordRow = false, afterTelecomReleased = call.onFallbackToCarrier)
    }

    private fun finish(
        call: ActiveCall,
        recordRow: Boolean = true,
        afterTelecomReleased: (() -> Unit)? = null,
    ) {
        if (active !== call) {
            afterTelecomReleased?.invoke()
            return
        }
        active = null
        call.timeoutJob?.cancel()
        call.stateJob?.cancel()
        call.sessionScope.cancel()
        telecom.remove(call.handle.id) { afterTelecomReleased?.invoke() }
        ui.detachAudioControls()
        ui.clearNotification()
        ui.stopCallService()
        ui.removed(call.handle.id)
        if (!recordRow) return
        val ended = call.handle.state as? VoipCallState.Ended ?: return
        val record = arkCallRecordOf(
            handle = call.handle,
            direction = call.direction,
            endReason = ended.reason,
            endedAtMillis = clock.nowMillis(),
            answeredByUser = call.answeredByUser,
        )
        callLog.record(record)
        if (record.type == ArkCallType.MISSED) missedCalls(record)
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
