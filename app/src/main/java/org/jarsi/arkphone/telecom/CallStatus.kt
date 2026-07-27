package org.jarsi.arkphone.telecom

import android.telecom.Call

enum class CallStatus { RINGING, DIALING, ACTIVE, HOLDING, DISCONNECTING, DISCONNECTED, UNKNOWN }

/** A call the user is (or was) actually on, as opposed to one just ringing. */
val CallStatus.isOffHook: Boolean
    get() = this == CallStatus.DIALING || this == CallStatus.ACTIVE || this == CallStatus.HOLDING

fun mapTelecomState(state: Int): CallStatus = when (state) {
    Call.STATE_RINGING -> CallStatus.RINGING
    Call.STATE_DIALING, Call.STATE_CONNECTING -> CallStatus.DIALING
    Call.STATE_ACTIVE -> CallStatus.ACTIVE
    Call.STATE_HOLDING -> CallStatus.HOLDING
    Call.STATE_DISCONNECTING -> CallStatus.DISCONNECTING
    Call.STATE_DISCONNECTED -> CallStatus.DISCONNECTED
    else -> CallStatus.UNKNOWN
}
