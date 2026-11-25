package org.jarsi.arkphone.ui.incall

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import androidx.test.core.app.ApplicationProvider
import org.jarsi.arkphone.MainActivity
import org.jarsi.arkphone.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppTask

@RunWith(RobolectricTestRunner::class)
class InCallTaskTest {
    @Test
    fun cleanupTargetsOnlyTheInCallBaseIntentWhenNoActivityIsAlive() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        fun task(intent: Intent) = ShadowAppTask.newInstance().also { task ->
            shadowOf(task).setTaskInfo(ActivityManager.RecentTaskInfo().apply { baseIntent = intent })
        }
        val main = task(Intent(context, MainActivity::class.java))
        val messages = task(Intent().setClassName(context, "org.jarsi.arkphone.MessagesLauncher"))
        val call = task(InCallActivity.intent(context))
        shadowOf(context.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(main, messages, call))
        InCallActivity.removeTask(context)
        assertEquals(true, shadowOf(call).isFinishedAndRemoved)
        assertEquals(false, shadowOf(main).isFinishedAndRemoved)
        assertEquals(false, shadowOf(messages).isFinishedAndRemoved)
    }

    @Test
    fun callTaskIsVisibleIsolatedLabelledAndAutomaticallyRemoved() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, InCallActivity::class.java), 0)
        assertEquals(0, info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS)
        assertNotEquals(0, info.flags and ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_INSTANCE, info.launchMode)
        assertNotEquals(context.applicationInfo.taskAffinity, info.taskAffinity)
        assertEquals(R.string.notification_ongoing_title, info.labelRes)
    }
}
