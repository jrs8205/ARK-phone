package org.jarsi.arkphone.telecom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the screen off while the phone is held to the ear during a call and
 * — the part a missing implementation breaks — turns it back on the moment
 * the phone is taken away. Backed by the platform proximity wake lock, the
 * same mechanism AOSP Dialer and Fossify Phone use.
 */
interface ProximityLock {
    fun acquire()
    fun release()
}

/**
 * The screen is given to the proximity sensor only while call audio plays
 * through the earpiece: on speaker, Bluetooth or a wired headset the user is
 * looking at the phone, and during ring the screen must stay usable for
 * answering.
 */
internal fun shouldHoldProximityLock(
    statuses: List<CallStatus>,
    earpieceRoute: Boolean,
): Boolean {
    if (!earpieceRoute) return false
    return statuses.any {
        it == CallStatus.DIALING || it == CallStatus.ACTIVE || it == CallStatus.HOLDING
    }
}

/** Applies [shouldHoldProximityLock] to every call and audio-route change. */
@Singleton
class ProximityController @Inject constructor(
    callController: CallController,
    private val proximityLock: ProximityLock,
    @ApplicationScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            combine(callController.calls, callController.audio) { calls, audio ->
                shouldHoldProximityLock(calls.map { it.status }, audio.earpiece)
            }
                .distinctUntilChanged()
                .collect { hold -> if (hold) proximityLock.acquire() else proximityLock.release() }
        }
    }
}
