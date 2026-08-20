package org.jarsi.arkphone.telecom

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BlockedCallNotifierTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private var nowMillis = 100_000L
    private val clock = Clock { nowMillis }

    private fun TestScope.notifier(
        contacts: FakeContactsRepository = FakeContactsRepository(),
    ): BlockedCallNotifier {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return BlockedCallNotifier(context, contacts, clock, CoroutineScope(dispatcher))
    }

    private fun postedNotification() = shadowOf(
        context.getSystemService(NotificationManager::class.java),
    ).getNotification(BlockedCallNotifier.NOTIFICATION_ID)

    @Test
    fun postsOnTheSilentBlockedChannel() = runTest {
        notifier().onCallBlocked("0401234567")
        val notification = postedNotification()
        assertNotNull(notification)
        assertEquals(BlockedCallNotifier.CHANNEL_BLOCKED, notification.channelId)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(BlockedCallNotifier.CHANNEL_BLOCKED)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun showsThePhoneIconNotTheAppBubble() = runTest {
        // The shared bubble logo reads as a text message in the status bar
        // (field report 2026-08-20): call notifications carry a handset.
        notifier().onCallBlocked("0401234567")
        assertEquals(R.drawable.ic_notification_call, postedNotification().smallIcon.resId)
    }

    @Test
    fun resolvesTheCallerNameLikeTheMissedNotification() = runTest {
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["0401234567"] = ContactMatch("Alice", null)
        }
        notifier(contacts).onCallBlocked("0401234567")
        assertEquals(
            "Alice",
            postedNotification().extras.getCharSequence(
                androidx.core.app.NotificationCompat.EXTRA_TEXT,
            ),
        )
    }

    @Test
    fun blockedCallsAccumulateUntilSeen() = runTest {
        val notifier = notifier()
        notifier.onCallBlocked("0401234567")
        notifier.onCallBlocked("0501112223")
        assertEquals(2, postedNotification().number)
        notifier.onSeen()
        assertNull(postedNotification())
        notifier.onCallBlocked("0401234567")
        assertEquals(1, postedNotification().number)
    }

    @Test
    fun remembersRecentBlocksForTheMissedSuppression() = runTest {
        val notifier = notifier()
        notifier.onCallBlocked("0401234567")
        assertTrue(notifier.wasRecentlyBlocked("0401234567"))
        assertTrue(notifier.wasRecentlyBlocked("+358401234567"))
        assertFalse(notifier.wasRecentlyBlocked("0501112223"))
    }

    @Test
    fun forgetsBlocksOlderThanTheWindow() = runTest {
        val notifier = notifier()
        notifier.onCallBlocked("0401234567")
        nowMillis += BlockedCallNotifier.RECENT_WINDOW_MILLIS + 1
        assertFalse(notifier.wasRecentlyBlocked("0401234567"))
    }

    @Test
    fun aHiddenBlockMatchesOnlyAHiddenMissedCall() = runTest {
        val notifier = notifier()
        notifier.onCallBlocked(null)
        assertTrue(notifier.wasRecentlyBlocked(null))
        assertFalse(notifier.wasRecentlyBlocked("0401234567"))
    }
}
