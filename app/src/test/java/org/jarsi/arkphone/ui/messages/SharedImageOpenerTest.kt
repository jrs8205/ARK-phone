package org.jarsi.arkphone.ui.messages

import android.app.Activity
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.FakeTelephonyProvider
import org.jarsi.arkphone.messaging.MessagingNavigator
import org.jarsi.arkphone.ui.conversation.ConversationActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class SharedImageOpenerTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Before
    fun setUp() {
        val provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms", provider)
    }

    @Test
    fun `a share opens its conversation even when the sharing activity is gone`() = runTest {
        // The user backed out (or the system destroyed the activity) while
        // the image was being copied: the share must still land, from the
        // application context in its own task.
        val opener = SharedImageOpener(
            appContext = context,
            messagingNavigator = MessagingNavigator(context, backgroundScope, Dispatchers.Unconfined),
            scope = backgroundScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        opener.open(listOf("+358400000000"), "Moro", source = null, host = { null })
        runCurrent()

        val started = shadowOf(context).nextStartedActivity
        assertNotNull(started)
        assertEquals(ConversationActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a live sharing activity keeps the conversation in its own task`() = runTest {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val opener = SharedImageOpener(
            appContext = context,
            messagingNavigator = MessagingNavigator(context, backgroundScope, Dispatchers.Unconfined),
            scope = backgroundScope,
            ioDispatcher = Dispatchers.Unconfined,
        )

        opener.open(listOf("+358400000000"), null, source = null, host = { activity })
        runCurrent()

        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
