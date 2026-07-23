package org.jarsi.arkphone.telecom

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ArkInCallService : InCallService() {

    @Inject lateinit var callController: CallController
    @Inject lateinit var callNotifications: CallNotifications

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
        when (status) {
            CallStatus.RINGING -> callNotifications.showIncomingCall(info)
            CallStatus.DIALING, CallStatus.ACTIVE -> {
                callNotifications.showOngoingCall(info)
                if (previous == null || previous == CallStatus.RINGING) {
                    startActivity(org.jarsi.arkphone.ui.incall.InCallActivity.intent(this))
                }
            }
            CallStatus.DISCONNECTED -> callNotifications.clear()
            else -> Unit
        }
    }
}
