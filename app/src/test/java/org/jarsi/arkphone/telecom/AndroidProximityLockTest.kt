package org.jarsi.arkphone.telecom

import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
class AndroidProximityLockTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun supportProximity() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
    }

    @Test
    fun acquireHoldsProximityWakeLock() {
        supportProximity()
        val lock = AndroidProximityLock(context)
        lock.acquire()
        val latest = ShadowPowerManager.getLatestWakeLock()
        assertNotNull(latest)
        assertTrue(latest.isHeld)
    }

    @Test
    fun releaseLetsGoOfTheWakeLock() {
        supportProximity()
        val lock = AndroidProximityLock(context)
        lock.acquire()
        lock.release()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun doubleAcquireAndDoubleReleaseAreSafe() {
        supportProximity()
        val lock = AndroidProximityLock(context)
        lock.acquire()
        lock.acquire()
        lock.release()
        lock.release()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun releaseWithoutAcquireIsSafe() {
        supportProximity()
        val lock = AndroidProximityLock(context)
        lock.release()
    }

    @Test
    fun deviceWithoutProximitySensorIsSafe() {
        // Robolectric's default: the wake-lock level is unsupported.
        val lock = AndroidProximityLock(context)
        lock.acquire()
        lock.release()
    }
}
