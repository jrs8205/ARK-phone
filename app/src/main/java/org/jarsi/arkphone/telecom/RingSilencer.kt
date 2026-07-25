package org.jarsi.arkphone.telecom

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
        callNotifications.silenceRinging(info)
        callerAnnouncer.onRingingStopped(info.id)
    }
}
