package org.jarsi.arkphone.messaging

import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
class MessageNotifierTest {

    private lateinit var notifier: AndroidMessageNotifier
    private lateinit var shadowManager: ShadowNotificationManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        notifier = AndroidMessageNotifier(context)
        shadowManager = shadowOf(
            context.getSystemService(NotificationManager::class.java),
        )
    }

    @Test
    fun `the channel badges at high importance`() {
        notifier.notifyMessage(3L, "+358441234567", "Matti", "Moro", 1_000L)
        val channel = shadowManager.notificationChannels
            .single { (it as android.app.NotificationChannel).id == AndroidMessageNotifier.CHANNEL_MESSAGES }
            as android.app.NotificationChannel
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertTrue(channel.canShowBadge())
    }

    @Test
    fun `each thread gets its own notification id`() {
        notifier.notifyMessage(3L, "+358441234567", "Matti", "Moro", 1_000L)
        notifier.notifyMessage(4L, "+358400000000", null, "Terve", 2_000L)
        assertEquals(2, shadowManager.allNotifications.size)
        assertTrue(shadowManager.getNotification(AndroidMessageNotifier.notificationIdFor(3L)) != null)
        assertTrue(shadowManager.getNotification(AndroidMessageNotifier.notificationIdFor(4L)) != null)
    }

    @Test
    fun `the notification carries reply and mark read actions`() {
        notifier.notifyMessage(3L, "+358441234567", "Matti", "Moro", 1_000L)
        val notification =
            shadowManager.getNotification(AndroidMessageNotifier.notificationIdFor(3L))
        assertEquals(2, notification.actions.size)
    }

    @Test
    fun `the reply action carries the sim the message arrived on`() {
        notifier.notifyMessage(3L, "+358441234567", "Matti", "Moro", 1_000L, subscriptionId = 5)
        val notification =
            shadowManager.getNotification(AndroidMessageNotifier.notificationIdFor(3L))
        val replyIntent = shadowOf(notification.actions.first().actionIntent).savedIntent
        assertEquals(
            5,
            replyIntent.getIntExtra(MessageActionReceiver.EXTRA_SUBSCRIPTION_ID, -1),
        )
    }

    @Test
    fun `cancelling a thread removes only its notification`() {
        notifier.notifyMessage(3L, "+358441234567", "Matti", "Moro", 1_000L)
        notifier.notifyMessage(4L, "+358400000000", null, "Terve", 2_000L)
        notifier.cancelThread(3L)
        assertEquals(1, shadowManager.allNotifications.size)
    }
}
