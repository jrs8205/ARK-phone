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
 * through the earpiece AND the in-call screen is what the user left on top:
 * on speaker, Bluetooth or a wired headset the user is looking at the phone,
 * during ring the screen must stay usable for answering, and a user who has
 * switched to another app mid-call is USING the screen — an armed sensor
 * there blacked the display on every reach toward the status bar and locked
 * the user out of their own call (field-hit 2026-08-09 19:35).
 */
internal fun shouldHoldProximityLock(
    statuses: List<CallStatus>,
    earpieceRoute: Boolean,
    inCallUiVisible: Boolean,
): Boolean = earpieceRoute && inCallUiVisible && statuses.any { it.isOffHook }

/** Applies [shouldHoldProximityLock] to every call, route and UI change. */
@Singleton
class ProximityController @Inject constructor(
    callController: CallController,
    private val proximityLock: ProximityLock,
    @ApplicationScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            combine(
                callController.calls,
                callController.audio,
                callController.inCallUiVisible,
            ) { calls, audio, uiVisible ->
                shouldHoldProximityLock(calls.map { it.status }, audio.earpiece, uiVisible)
            }
                .distinctUntilChanged()
                .collect { hold -> if (hold) proximityLock.acquire() else proximityLock.release() }
        }
    }
}
