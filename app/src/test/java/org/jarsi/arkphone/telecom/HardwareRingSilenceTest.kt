package org.jarsi.arkphone.telecom

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import androidx.test.core.app.ApplicationProvider
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.VoipCallState
import org.jarsi.arkphone.voip.telecom.CallControllerVoipCallUi
import org.jarsi.arkphone.voip.telecom.VoipCallActions
import org.jarsi.arkphone.voip.telecom.VoipCallDirection
import org.jarsi.arkphone.voip.telecom.VoipCallHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HardwareRingSilenceTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val power = context.getSystemService(PowerManager::class.java)
    private val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
    private val screenOff = RingingScreenOff(context)
    private lateinit var service: ArkInCallService
    private lateinit var ui: CallControllerVoipCallUi

    @Before fun setup() {
        service = Robolectric.buildService(ArkInCallService::class.java).create().get()
        service.ringSilencer = AndroidRingSilencer(service.callNotifications, service.callerAnnouncer,
            screenOff, service.carrierRingAlerts)
        ui = CallControllerVoipCallUi(context, service.callController, service.callNotifications,
            service.callerAnnouncer, service.settingsCache, service.ringSilencer, screenOff)
        shadowOf(power).setIsInteractive(true)
    }

    private fun screen(interactive: Boolean) {
        shadowOf(power).setIsInteractive(interactive)
        context.sendBroadcast(Intent(if (interactive) Intent.ACTION_SCREEN_ON else Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun incoming() = VoipCallHandle("ark-1", "123", "Caller", VoipCallDirection.INCOMING,
        object : VoipCallActions {
            override fun answer() = Unit
            override fun reject() = Unit
            override fun hangUp() = Unit
        }, Clock { 1234L }).also { ui.added(it); ui.showIncoming(it) }

    private fun channel() = notifications.getNotification(CallNotifications.NOTIFICATION_ID)?.channelId

    private fun receivers() = shadowOf(context).getReceiversForIntent(Intent(Intent.ACTION_SCREEN_OFF))

    @Test fun telecomSilencesAllRingingCarrierHandlesOncePerRequest() {
        val silenced = mutableListOf<String>()
        service.ringSilencer = RingSilencer { silenced += it.id }
        val handles = listOf(FakeCallHandle("one"), FakeCallHandle("two"),
            FakeCallHandle("internet", viaArkCall = true),
            FakeCallHandle("active", telecomState = Call.STATE_ACTIVE),
            FakeCallHandle("held", telecomState = Call.STATE_HOLDING))
        handles.forEach(service.callController::onCallAdded)
        service.onSilenceRinger()
        assertEquals(listOf("one", "two"), silenced)
        assertEquals(1, ShadowLog.getLogsForTag("ArkPhone").count { it.msg == "Ring silence source=telecom" })
        service.callController.onCallRemoved("one")
        service.callController.onCallRemoved("two")
        service.onSilenceRinger()
        assertEquals(listOf("one", "two"), silenced)
        assertEquals(2, ShadowLog.getLogsForTag("ArkPhone").count { it.msg == "Ring silence source=telecom" })
    }

    @Test fun delayedCarrierRulesRememberSilenceWithoutPublishingABlockedCall() {
        service.callController.onCallAdded(FakeCallHandle())
        val ring = service.carrierRingAlerts.begin("call-1")
        service.onSilenceRinger()
        assertEquals(null, channel())
        assertEquals(true, ring.silenced)
        service.onSilenceRinger()
        assertEquals(null, channel())
        ring.admitted = true
        service.ringSilencer.silenceRinging(service.callController.calls.value.single())
        assertEquals(CallNotifications.CHANNEL_INCOMING_SILENCED, channel())
        service.carrierRingAlerts.end("call-1")
        assertFalse(service.carrierRingAlerts.begin("call-1").silenced)
    }

    @Test fun ringingWhileAsleepWaitsForAnInteractiveScreenBeforeArming() {
        screen(false)
        val baseline = receivers().size
        incoming()
        assertEquals(baseline + 1, receivers().size)
        val original = channel()
        screen(false)
        assertEquals(original, channel())
        screen(true)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertEquals(true, lock.isHeld)
        // A proximity-blanked panel can still have an interactive device.
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(original, channel())
        screen(false)
        assertEquals(CallNotifications.CHANNEL_INCOMING_SILENCED, channel())
        assertFalse(lock.isHeld)
        assertEquals(baseline, receivers().size)
    }

    @Test fun overlappingWatchesShareOneReceiverAndLogOneScreenOff() {
        val baseline = receivers().size
        val silenced = mutableListOf<String>()
        screenOff.watch("one") { silenced += "one" }
        screenOff.watch("two") { silenced += "two" }
        assertEquals(baseline + 1, receivers().size)
        screen(false)
        assertEquals(listOf("one", "two"), silenced)
        assertEquals(baseline, receivers().size)
        assertEquals(1, ShadowLog.getLogsForTag("ArkPhone").count { it.msg == "Ring silence source=screen_off" })
    }

    @Test fun unprotectedTimeoutDoesNotSilenceAndTheNextWakeRearms() {
        incoming()
        val original = channel()
        ShadowPowerManager.getLatestWakeLock().release()
        screen(false)
        assertEquals(original, channel())
        screen(true)
        screen(false)
        assertEquals(CallNotifications.CHANNEL_INCOMING_SILENCED, channel())
    }

    @Test fun answerUnregistersImmediatelyWhileMediaIsStillConnecting() {
        val baseline = receivers().size
        val call = incoming()
        assertEquals(baseline + 1, receivers().size)
        ui.silenceRinging(call)
        assertEquals(baseline, receivers().size)
        call.onState(VoipCallState.InCall)
        ui.changed()
        ui.showOngoing(call)
        screen(false)
        assertEquals(CallNotifications.CHANNEL_ONGOING, channel())
    }

    @Test fun localDeclineDropsTheScreenOffReceiver() {
        val baseline = receivers().size
        val call = incoming()
        call.onState(VoipCallState.Ended("local-reject"))
        ui.changed()
        ui.clearNotification()
        ui.removed(call.id)
        assertEquals(baseline, receivers().size)
        screen(false)
        assertEquals(null, channel())
    }

    @Test fun leavingRingingOrRemovingTheHandleDisarmsEvenAQueuedOldBroadcast() {
        val baseline = receivers()
        val call = incoming()
        val old = (receivers() - baseline.toSet()).single()
        call.onState(VoipCallState.Ended("remote-hangup"))
        ui.changed()
        assertEquals(baseline.size, receivers().size)
        ui.clearNotification()
        ui.removed(call.id)
        val next = incoming()
        val before = channel()
        shadowOf(power).setIsInteractive(false)
        old.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        assertEquals(before, channel())
        ui.removed(next.id)
        ui.clearNotification()
        screen(false)
        assertEquals(null, channel())
        assertEquals(baseline.size, receivers().size)
    }

    @Test fun telecomCallbackRepostsQuietlyAndLeavesTheCallAnswerable() {
        val handle = FakeCallHandle()
        service.callController.onCallAdded(handle)
        service.callNotifications.ensureChannels()
        service.callNotifications.showIncomingCall(service.callController.calls.value.single())
        service.onSilenceRinger()
        assertEquals(CallNotifications.CHANNEL_INCOMING_SILENCED, channel())
        assertEquals(CallStatus.RINGING, service.callController.calls.value.single().status)
        assertFalse(handle.rejected)
    }

    @Test fun screenOffDuringAnInternetRingRepostsQuietly() {
        incoming()
        screen(false)
        assertEquals(CallNotifications.CHANNEL_INCOMING_SILENCED, channel())
        assertEquals(CallStatus.RINGING, service.callController.calls.value.single().status)
    }
}
