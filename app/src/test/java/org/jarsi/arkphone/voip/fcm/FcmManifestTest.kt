package org.jarsi.arkphone.voip.fcm

import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The README promises that nothing leaves the phone until the user turns ARK
 * calls on. Firebase would otherwise mint a push token at process start.
 */
@RunWith(RobolectricTestRunner::class)
class FcmManifestTest {

    @Test
    fun `firebase auto-init is switched off in the manifest`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        val metaData = info.metaData
        assertTrue("meta-data missing", metaData != null && metaData.containsKey("firebase_messaging_auto_init_enabled"))
        assertFalse(metaData.getBoolean("firebase_messaging_auto_init_enabled", true))
    }
}
