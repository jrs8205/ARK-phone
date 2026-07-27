package org.jarsi.arkphone.telecom

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MissedCallNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun TestScope.notifier(
        contacts: FakeContactsRepository = FakeContactsRepository(),
    ): MissedCallNotifier {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return MissedCallNotifier(context, contacts, CoroutineScope(dispatcher), dispatcher)
    }

    private fun postedNotification() = shadowOf(
        context.getSystemService(NotificationManager::class.java),
    ).getNotification(MissedCallNotifier.NOTIFICATION_ID)

    @Test
    fun postsNotificationWithResolvedContactName() = runTest {
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["0401234567"] = ContactMatch("Alice", null)
        }
        notifier(contacts).onMissedCallsChanged(1, "0401234567")
        val notification = postedNotification()
        assertNotNull(notification)
        assertEquals("Missed call", notification.extras.getCharSequence(NotificationCompat.EXTRA_TITLE))
        assertEquals("Alice", notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT))
        assertEquals(1, notification.number)
    }

    @Test
    fun zeroCountCancelsTheNotification() = runTest {
        val notifier = notifier()
        notifier.onMissedCallsChanged(1, "0401234567")
        assertNotNull(postedNotification())
        notifier.onMissedCallsChanged(0, null)
        assertNull(postedNotification())
    }

    @Test
    fun callBackActionBroadcastsToCallActionReceiver() = runTest {
        notifier().onMissedCallsChanged(1, "0401234567")
        val action = postedNotification().actions.single()
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(ComponentName(context, CallActionReceiver::class.java), intent.component)
        assertEquals(MissedCallNotifier.ACTION_CALL_BACK, intent.action)
        assertEquals("0401234567", intent.getStringExtra(MissedCallNotifier.EXTRA_NUMBER))
    }

    @Test
    fun dismissingTheNotificationMarksTheCallLogSeen() = runTest {
        // Without this the swiped-away calls stay unread in the system log
        // and the platform re-broadcasts them as a fresh notification on the
        // next boot.
        notifier().onMissedCallsChanged(1, "0401234567")
        val delete = postedNotification().deleteIntent
        assertNotNull(delete)
        val intent = shadowOf(delete).savedIntent
        assertEquals(ComponentName(context, CallActionReceiver::class.java), intent.component)
        assertEquals(MissedCallNotifier.ACTION_MISSED_DISMISSED, intent.action)
    }
}
