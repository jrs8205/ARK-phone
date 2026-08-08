package org.jarsi.arkphone.voip.telecom

import android.telecom.Call
import org.jarsi.arkphone.telecom.CallHandle
import org.jarsi.arkphone.telecom.DisconnectError
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.VoipCallState

enum class VoipCallDirection { INCOMING, OUTGOING }

/** What the in-call UI's buttons do to the media session. */
interface VoipCallActions {
    fun answer()
    fun reject()
    fun hangUp()
}

/**
 * A VoIP call seen through the interface ARK's existing call UI already
 * consumes, so InCallActivity, the notifications and the Google task model
 * work on it unchanged.
 */
class VoipCallHandle(
    override val id: String,
    override val number: String?,
    override val displayName: String?,
    private val direction: VoipCallDirection,
    private val actions: VoipCallActions,
    private val clock: Clock,
) : CallHandle {

    var state: VoipCallState = VoipCallState.Idle
        private set

    override var connectTimeMillis: Long = 0L
        private set

    /** Calls over the internet never belong to a SIM. */
    override val simAccountId: String? = null

    /** Nothing in the VoIP path produces a platform DisconnectCause. */
    override val disconnectError: DisconnectError? = null

    override val viaArkCall: Boolean = true

    override val telecomState: Int
        get() = when (state) {
            VoipCallState.Idle ->
                if (direction == VoipCallDirection.INCOMING) {
                    Call.STATE_RINGING
                } else {
                    Call.STATE_CONNECTING
                }
            VoipCallState.Connecting ->
                if (direction == VoipCallDirection.INCOMING) {
                    // The user answered; media is still being set up.
                    Call.STATE_CONNECTING
                } else {
                    Call.STATE_DIALING
                }
            is VoipCallState.Ringing -> Call.STATE_RINGING
            VoipCallState.InCall -> Call.STATE_ACTIVE
            is VoipCallState.Ended -> Call.STATE_DISCONNECTED
        }

    fun onState(state: VoipCallState) {
        this.state = state
        // Stamped once: the call duration must survive the disconnect so the
        // ended screen and the call-log row agree on it.
        if (state == VoipCallState.InCall && connectTimeMillis == 0L) {
            connectTimeMillis = clock.nowMillis()
        }
    }

    override fun answer() = actions.answer()
    override fun reject() = actions.reject()
    override fun disconnect() = actions.hangUp()

    // Phase 1 ARK calls have no hold and no in-band DTMF; the buttons stay
    // inert rather than pretending to do something.
    override fun hold() = Unit
    override fun unhold() = Unit
    override fun playDtmf(digit: Char) = Unit
    override fun stopDtmf() = Unit
}
