package org.jarsi.arkphone.telecom

import android.util.Log
import javax.inject.Inject

/** Stops our ringtone and announcement for a ringing call without rejecting it. */
fun interface RingSilencer {
    fun silenceRinging(info: CallInfo)
}

class AndroidRingSilencer @Inject constructor(
    private val callNotifications: CallNotifications,
    private val callerAnnouncer: CallerAnnouncer,
) : RingSilencer {
    override fun silenceRinging(info: CallInfo) {
        // Announcement first, and never let a notification failure take the
        // in-call UI down with it — that hands the call to the system dialer.
        callerAnnouncer.onRingingStopped(info.id)
        runCatching { callNotifications.silenceRinging(info) }
            .onFailure { Log.e("ArkPhone", "Silencing the ring notification failed", it) }
    }
}
