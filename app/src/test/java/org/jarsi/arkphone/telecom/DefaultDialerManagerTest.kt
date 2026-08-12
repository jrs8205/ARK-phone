package org.jarsi.arkphone.telecom

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DefaultDialerManagerTest {

    private val manager = DefaultDialerManager(
        context = ApplicationProvider.getApplicationContext<Application>(),
        permissionChecker = FakePermissionChecker(),
    )

    @Test
    fun `core permissions include reading own phone numbers`() {
        // Group-MMS threading filters this phone's own numbers out of the
        // recipient set; without READ_PHONE_NUMBERS that lookup silently
        // resolves empty and every received group forks a parallel thread.
        assertTrue(Manifest.permission.READ_PHONE_NUMBERS in manager.corePermissions())
    }
}
