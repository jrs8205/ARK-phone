package org.jarsi.arkphone.telecom

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.ui.incall.InCallActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallNotificationsTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun incomingCall() = CallInfo(
        id = "call-1",
        number = "0401234567",
        displayName = "Alice",
        status = CallStatus.RINGING,
        connectedAtMillis = null,
    )

    @Test
    fun incomingNotificationUsesCallStyleWithFullScreenIntent() {
        val notification = CallNotifications(context).buildIncomingCall(incomingCall())
        assertEquals(NotificationCompat.CATEGORY_CALL, notification.category)
        assertNotNull(notification.fullScreenIntent)
        assertEquals(
            NotificationCompat.CallStyle.CALL_TYPE_INCOMING,
            notification.extras.getInt(NotificationCompat.EXTRA_CALL_TYPE),
        )
        assertEquals(2, notification.actions.size)
    }

    @Test
    fun incomingNotificationIsFullyVisibleOnTheLockScreen() {
        val notification = CallNotifications(context).buildIncomingCall(incomingCall())
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
    }

    @Test
    fun answerActionOpensInCallActivityWithAnswerAction() {
        val notification = CallNotifications(context).buildIncomingCall(incomingCall())
        val activityIntents = notification.actions.mapNotNull { action ->
            shadowOf(action.actionIntent).savedIntent
        }.filter { it.component == ComponentName(context, InCallActivity::class.java) }
        assertEquals(1, activityIntents.size)
        val answer = activityIntents.single()
        assertEquals(CallNotifications.ACTION_ANSWER, answer.action)
        assertEquals("call-1", answer.getStringExtra(CallNotifications.EXTRA_CALL_ID))
    }

    @Test
    fun declineActionStaysABroadcastToCallActionReceiver() {
        val notification = CallNotifications(context).buildIncomingCall(incomingCall())
        val broadcastIntents = notification.actions.mapNotNull { action ->
            shadowOf(action.actionIntent).savedIntent
        }.filter { it.component == ComponentName(context, CallActionReceiver::class.java) }
        assertEquals(1, broadcastIntents.size)
        val decline = broadcastIntents.single()
        assertEquals(CallNotifications.ACTION_DECLINE, decline.action)
        assertEquals("call-1", decline.getStringExtra(CallNotifications.EXTRA_CALL_ID))
    }

    @Test
    fun callerNameFallsBackToNumberThenUnknown() {
        val named = CallNotifications(context).buildIncomingCall(incomingCall())
        assertTrue(named.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.contains("Alice") == true)
    }

    @Test
    fun silencedRingHasNoHeadsUpAndNoInsistentRing() {
        val notification = CallNotifications(context)
            .buildIncomingCall(incomingCall(), silentRing = true, quiet = true)
        assertEquals(CallNotifications.CHANNEL_INCOMING_SILENCED, notification.channelId)
        assertNull(notification.fullScreenIntent)
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
    }

    @Test
    fun voiceOnlyModePostsOnTheSilentChannel() {
        val notifications = CallNotifications(context)
        assertEquals(
            CallNotifications.CHANNEL_INCOMING,
            notifications.buildIncomingCall(incomingCall()).channelId,
        )
        assertEquals(
            CallNotifications.CHANNEL_INCOMING_SILENT,
            notifications.buildIncomingCall(incomingCall(), silentRing = true).channelId,
        )
    }
}
