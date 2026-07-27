package org.jarsi.arkphone.telecom

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidProximityLock @Inject constructor(
    @ApplicationContext context: Context,
) : ProximityLock {

    private val wakeLock: PowerManager.WakeLock? by lazy {
        context.getSystemService(PowerManager::class.java)
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ArkPhone:proximity")
            ?.apply { setReferenceCounted(false) }
    }

    // No timeout: the lock must last for the whole call, and every release
    // path ends at ProximityController seeing the call list empty out.
    @SuppressLint("WakelockTimeout")
    override fun acquire() {
        wakeLock?.takeIf { !it.isHeld }?.acquire()
    }

    override fun release() {
        // The wait flag keeps the screen dark until the phone actually leaves
        // the ear, so ending a call mid-ear does not light the cheek.
        wakeLock?.takeIf { it.isHeld }
            ?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
    }
}
