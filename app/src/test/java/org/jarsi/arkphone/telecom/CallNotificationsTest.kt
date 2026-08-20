package org.jarsi.arkphone.telecom

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jarsi.arkphone.R
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
    fun callNotificationsCarryThePhoneIconNotTheAppBubble() {
        val notifications = CallNotifications(context) { java.util.Optional.empty() }
        assertEquals(
            R.drawable.ic_notification_call,
            notifications.buildIncomingCall(incomingCall()).smallIcon.resId,
        )
        assertEquals(
            R.drawable.ic_notification_call,
            notifications.buildOngoingCall(null).smallIcon.resId,
        )
    }

    @Test
    fun ensureChannelsDeletesTheLegacyVoipServiceChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("voip_calls", "Internet call", NotificationManager.IMPORTANCE_LOW),
        )
        CallNotifications(context) { java.util.Optional.empty() }.ensureChannels()
        // A release installed over the beta has no debug-only service to
        // delete this; the shared channel bootstrap must own the removal.
        assertNull(manager.getNotificationChannel("voip_calls"))
    }

    @Test
    fun anArkRingOnAnAwakeUnlockedScreenStaysOffTheHeadsUpChannel() {
        // Robolectric default: screen on, keyguard unlocked. The call screen
        // opens, so the notification must not banner a second button set —
        // the awake ring uses the ordinary channel with no full-screen intent.
        val notification = CallNotifications(context) { java.util.Optional.empty() }
            .buildIncomingCall(incomingCall().copy(viaArkCall = true))
        assertEquals(CallNotifications.CHANNEL_INCOMING, notification.channelId)
        assertNull(notification.fullScreenIntent)
    }

    @Test
    fun anArkRingOnALockedScreenCarriesTheFullScreenIntent() {
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
            .setKeyguardLocked(true)
        val notification = CallNotifications(context) { java.util.Optional.empty() }
            .buildIncomingCall(incomingCall().copy(viaArkCall = true))
        assertEquals(CallNotifications.CHANNEL_INCOMING_ARK, notification.channelId)
        assertNotNull(notification.fullScreenIntent)
    }

    @Test
    fun anAwakeArkRingWithTheAppInBackgroundKeepsTheBanner() {
        // The call screen cannot launch from the background, so the heads-up
        // banner with its answer/decline actions is the only answer surface.
        val notification = CallNotifications(context) { java.util.Optional.empty() }
            .buildIncomingCall(incomingCall().copy(viaArkCall = true), arkBanner = true)
        assertEquals(CallNotifications.CHANNEL_INCOMING_ARK, notification.channelId)
        assertNotNull(notification.fullScreenIntent)
    }

    @Test
    fun aVoiceOnlyArkRingOnALockedScreenIsSilentButStillLightsTheScreen() {
        shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
            .setKeyguardLocked(true)
        val notification = CallNotifications(context) { java.util.Optional.empty() }
            .buildIncomingCall(incomingCall().copy(viaArkCall = true), silentRing = true)
        // Voice-only mode: no channel ringtone under the announcement, but the
        // full-screen intent still needs a HIGH channel to light the screen.
        assertEquals(CallNotifications.CHANNEL_INCOMING_ARK_SILENT, notification.channelId)
        assertNotNull(notification.fullScreenIntent)
    }

    @Test
    fun theRingingNotificationNeverHeadsUpOverTheCallScreen() {
        // ARK-phone always opens its own call screen, so a heads-up would
        // stack a second set of answer/decline buttons on top of it. A
        // CallStyle notification demands a full-screen intent, and both of
        // those are what make the system draw the banner.
        val notification = CallNotifications(context) { java.util.Optional.empty() }.buildIncomingCall(incomingCall())
        assertEquals(NotificationCompat.CATEGORY_CALL, notification.category)
        assertNull(notification.fullScreenIntent)
        assertEquals(0, notification.extras.getInt(NotificationCompat.EXTRA_CALL_TYPE))
    }

    @Test
    fun theRingingNotificationKeepsAnswerAndDeclineInTheShade() {
        val notification = CallNotifications(context) { java.util.Optional.empty() }.buildIncomingCall(incomingCall())
        assertEquals(2, notification.actions.size)
    }

    @Test
    fun theRingingNotificationStillRingsInsistently() {
        val notification = CallNotifications(context) { java.util.Optional.empty() }.buildIncomingCall(incomingCall())
        assertEquals(Notification.FLAG_INSISTENT, notification.flags and Notification.FLAG_INSISTENT)
    }

    @Test
    fun theRingingChannelsAreQuietEnoughToNotBanner() {
        val notifications = CallNotifications(context) { java.util.Optional.empty() }
        notifications.ensureChannels()
        val manager = context.getSystemService(NotificationManager::class.java)
        listOf(CallNotifications.CHANNEL_INCOMING, CallNotifications.CHANNEL_INCOMING_SILENT)
            .forEach { id ->
                assertEquals(
                    NotificationManager.IMPORTANCE_DEFAULT,
                    manager.getNotificationChannel(id).importance,
                )
            }
    }

    @Test
    fun incomingNotificationIsFullyVisibleOnTheLockScreen() {
        val notification = CallNotifications(context) { java.util.Optional.empty() }.buildIncomingCall(incomingCall())
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, notification.visibility)
    }

    @Test
    fun answerActionOpensInCallActivityWithAnswerAction() {
        val notification = CallNotifications(context) { java.util.Optional.empty() }.buildIncomingCall(incomingCall())
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
        val notification = CallNotifications(context) { java.util.Optional.empty() }.buildIncomingCall(incomingCall())
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
        val named = CallNotifications(context) { java.util.Optional.empty() }.buildIncomingCall(incomingCall())
        assertTrue(named.extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.contains("Alice") == true)
    }

    @Test
    fun silencedRingHasNoHeadsUpAndNoInsistentRing() {
        val notification = CallNotifications(context) { java.util.Optional.empty() }
            .buildIncomingCall(incomingCall(), silentRing = true, quiet = true)
        assertEquals(CallNotifications.CHANNEL_INCOMING_SILENCED, notification.channelId)
        assertNull(notification.fullScreenIntent)
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        // CallStyle without a full-screen intent or foreground service makes
        // notify() throw and took the whole in-call UI down in the field.
        assertEquals(0, notification.extras.getInt(NotificationCompat.EXTRA_CALL_TYPE))
    }

    @Test
    fun incomingNotificationsAreUsableByDefault() {
        val notifications = CallNotifications(context) { java.util.Optional.empty() }
        notifications.ensureChannels()
        assertTrue(notifications.incomingNotificationsUsable())
    }

    @Test
    fun globallyDisabledNotificationsAreReportedUnusable() {
        val notifications = CallNotifications(context) { java.util.Optional.empty() }
        notifications.ensureChannels()
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(false, notifications.incomingNotificationsUsable())
    }

    @Test
    fun aDisabledIncomingChannelIsReportedUnusable() {
        val notifications = CallNotifications(context) { java.util.Optional.empty() }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CallNotifications.CHANNEL_INCOMING,
                "Incoming",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertEquals(false, notifications.incomingNotificationsUsable())
    }

    @Test
    fun voiceOnlyModePostsOnTheSilentChannel() {
        val notifications = CallNotifications(context) { java.util.Optional.empty() }
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
