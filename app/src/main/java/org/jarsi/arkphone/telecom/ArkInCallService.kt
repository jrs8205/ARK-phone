package org.jarsi.arkphone.telecom

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import dagger.hilt.android.AndroidEntryPoint
import org.jarsi.arkphone.ui.incall.InCallActivity
import javax.inject.Inject

@AndroidEntryPoint
class ArkInCallService : InCallService() {

    @Inject lateinit var callController: CallController
    @Inject lateinit var callNotifications: CallNotifications
    @Inject lateinit var callerAnnouncer: CallerAnnouncer

    private val handlesByCall = mutableMapOf<Call, TelecomCallHandle>()
    private val callbacksByCall = mutableMapOf<Call, Call.Callback>()
    private val lastStatus = mutableMapOf<String, CallStatus>()

    override fun onCreate() {
        super.onCreate()
        callController.audioController = object : InCallAudioController {
            override fun applyMuted(muted: Boolean) = setMuted(muted)
            override fun applyRoute(speaker: Boolean) = setAudioRoute(
                if (speaker) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE,
            )
        }
    }

    override fun onDestroy() {
        callController.audioController = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        callNotifications.ensureChannels()
        val handle = TelecomCallHandle(call)
        handlesByCall[call] = handle
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = publishUpdate(handle)
            override fun onDetailsChanged(call: Call, details: Call.Details) =
                callController.onCallChanged()
        }
        call.registerCallback(callback)
        callbacksByCall[call] = callback
        callController.onCallAdded(handle)
        publishUpdate(handle)
    }

    override fun onCallRemoved(call: Call) {
        callbacksByCall.remove(call)?.let(call::unregisterCallback)
        handlesByCall.remove(call)?.let { handle ->
            lastStatus.remove(handle.id)
            callerAnnouncer.onRingingStopped(handle.id)
            callController.onCallRemoved(handle.id)
        }
        if (calls.isEmpty()) callNotifications.clear()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        callController.onAudioStateChanged(
            muted = audioState.isMuted,
            speakerOn = audioState.route == CallAudioState.ROUTE_SPEAKER,
        )
    }

    private fun publishUpdate(handle: TelecomCallHandle) {
        callController.onCallChanged()
        val status = mapTelecomState(handle.telecomState)
        if (lastStatus[handle.id] == status) return
        val previous = lastStatus[handle.id]
        lastStatus[handle.id] = status
        val info = callController.calls.value.firstOrNull { it.id == handle.id } ?: return
        if (status != CallStatus.RINGING) callerAnnouncer.onRingingStopped(info.id)
        when (status) {
            CallStatus.RINGING -> {
                callerAnnouncer.onRinging(info)
                callNotifications.showIncomingCall(info)
                if (shouldLaunchIncomingUiDirectly()) {
                    startActivity(InCallActivity.intent(this))
                }
            }
            CallStatus.DIALING, CallStatus.ACTIVE -> {
                callNotifications.showOngoingCall(info)
                if (previous == null || previous == CallStatus.RINGING) {
                    startActivity(InCallActivity.intent(this))
                }
            }
            CallStatus.DISCONNECTED -> callNotifications.clear()
            else -> Unit
        }
    }

    /**
     * The full-screen intent is unreliable while the keyguard is showing (and unavailable
     * without the full-screen-intent permission on API 34+), so launch the in-call screen
     * directly then. System-bound InCallServices are exempt from background-start limits,
     * and the singleTask/singleTop launch mode absorbs a duplicate start if the
     * full-screen intent fires too.
     */
    private fun shouldLaunchIncomingUiDirectly(): Boolean {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        val keyguardShowing = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return !interactive || keyguardShowing || !canUseFullScreenIntentCompat()
    }

    private fun canUseFullScreenIntentCompat(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false
    }
}
