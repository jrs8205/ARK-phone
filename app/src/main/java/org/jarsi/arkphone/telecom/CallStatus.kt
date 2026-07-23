package org.jarsi.arkphone.telecom

import android.telecom.Call

enum class CallStatus { RINGING, DIALING, ACTIVE, HOLDING, DISCONNECTING, DISCONNECTED, UNKNOWN }

fun mapTelecomState(state: Int): CallStatus = when (state) {
    Call.STATE_RINGING -> CallStatus.RINGING
    Call.STATE_DIALING, Call.STATE_CONNECTING -> CallStatus.DIALING
    Call.STATE_ACTIVE -> CallStatus.ACTIVE
    Call.STATE_HOLDING -> CallStatus.HOLDING
    Call.STATE_DISCONNECTING -> CallStatus.DISCONNECTING
    Call.STATE_DISCONNECTED -> CallStatus.DISCONNECTED
    else -> CallStatus.UNKNOWN
}
