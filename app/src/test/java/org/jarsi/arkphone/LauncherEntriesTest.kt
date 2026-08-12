package org.jarsi.arkphone

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LauncherEntriesTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the launcher offers a phone and a messages entry`() {
        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(context.packageName)
        val entries = context.packageManager.queryIntentActivities(launcher, 0)
            .associate {
                it.activityInfo.name to it.loadLabel(context.packageManager).toString()
            }
        assertEquals(
            mapOf(
                "org.jarsi.arkphone.MainActivity" to "Phone",
                "org.jarsi.arkphone.MessagesLauncher" to "Messages",
            ),
            entries,
        )
    }

    @Test
    fun `only the messages entry is recognized as a messages launch`() {
        assertTrue(
            MainActivity.opensMessages(
                Intent().setClassName(context, "org.jarsi.arkphone.MessagesLauncher"),
            ),
        )
        assertFalse(
            MainActivity.opensMessages(
                Intent().setClassName(context, "org.jarsi.arkphone.MainActivity"),
            ),
        )
        assertFalse(MainActivity.opensMessages(null))
    }
}
