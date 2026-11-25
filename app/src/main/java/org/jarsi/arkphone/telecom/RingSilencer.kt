package org.jarsi.arkphone.telecom

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** Stops our ringtone and announcement for a ringing call without rejecting it. */
fun interface RingSilencer {
    fun silenceRinging(info: CallInfo)
}

/** Admission belongs to one ringing interval. Until the blocking decision
 * admits it, even a quiet notification could expose a silently blocked call. */
@Singleton
class CarrierRingAlerts @Inject constructor() {
    class Ring {
        var admitted = false
        var silenced = false
    }

    private val rings = mutableMapOf<String, Ring>()

    fun begin(id: String): Ring = Ring().also { rings[id] = it }
    fun current(id: String): Ring? = rings[id]
    fun end(id: String) { rings.remove(id) }

    fun deferSilence(id: String): Boolean {
        val ring = rings[id] ?: return false
        ring.silenced = true
        return !ring.admitted
    }
}

class AndroidRingSilencer @Inject constructor(
    private val callNotifications: CallNotifications,
    private val callerAnnouncer: CallerAnnouncer,
    private val screenOff: RingingScreenOff,
    private val carrierRingAlerts: CarrierRingAlerts,
) : RingSilencer {
    override fun silenceRinging(info: CallInfo) {
        screenOff.stop(info.id)
        if (carrierRingAlerts.deferSilence(info.id)) return
        // Announcement first, and never let a notification failure take the
        // in-call UI down with it — that hands the call to the system dialer.
        callerAnnouncer.onRingingStopped(info.id)
        runCatching { callNotifications.silenceRinging(info) }
            .onFailure { Log.e("ArkPhone", "Silencing the ring notification failed", it) }
    }
}
