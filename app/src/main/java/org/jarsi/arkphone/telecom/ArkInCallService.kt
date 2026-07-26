package org.jarsi.arkphone.telecom

import android.app.KeyguardManager
import android.app.NotificationManager
import android.os.Build
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.ui.incall.InCallActivity
import javax.inject.Inject

@AndroidEntryPoint
class ArkInCallService : InCallService() {

    @Inject lateinit var callController: CallController
    @Inject lateinit var callNotifications: CallNotifications
    @Inject lateinit var callerAnnouncer: CallerAnnouncer
    @Inject lateinit var settingsCache: SettingsCache
    @Inject lateinit var ruleEvaluator: CallRuleEvaluator
    @Inject lateinit var rejectedCallReclassifier: RejectedCallReclassifier
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

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
            CallStatus.RINGING -> handleRinging(info)
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
     * The whole ringing path waits for the persisted settings: on a cold
     * start (telecom spawns the process for the incoming call) they have not
     * loaded by the time the call rings — a synchronous read is always the
     * default then. Rule evaluation comes first because Android never feeds
     * saved contacts to the screening service; a rule-blocked call is
     * rejected here before any ringtone, announcement or UI.
     */
    private fun handleRinging(info: CallInfo) {
        // The log row is dated at ring start, which is already in the past
        // here — pad the match window backwards to cover clock differences.
        val ringNotBeforeMillis = System.currentTimeMillis() - RING_START_MARGIN_MILLIS
        appScope.launch {
            val decision = ruleEvaluator.evaluate(info.number)
            val stillRinging = callController.calls.value
                .firstOrNull { it.id == info.id }?.status == CallStatus.RINGING
            if (!stillRinging) return@launch
            if (decision.block) {
                Log.i(TAG, "In-call rule block: ${decision.details}")
                callController.reject(info.id)
                rejectedCallReclassifier.markBlocked(info.number.orEmpty(), ringNotBeforeMillis)
                return@launch
            }
            val settings = settingsCache.current
            Log.i(
                TAG,
                "Ring channel decision: mode=${settings.announceMode} rule=${decision.details}",
            )
            callerAnnouncer.onRinging(info)
            callNotifications.showIncomingCall(
                info,
                silentRing = settings.announceMode == AnnounceMode.VOICE_ONLY,
            )
            if (shouldLaunchIncomingUiDirectly()) {
                startActivity(InCallActivity.intent(this@ArkInCallService))
            }
        }
    }

    private companion object {
        const val TAG = "ArkPhone"
        const val SETTINGS_WAIT_TIMEOUT_MILLIS = 500L
        const val RING_START_MARGIN_MILLIS = 10_000L
    }

    /**
     * The full-screen intent is unreliable while the keyguard is showing (and unavailable
     * without the full-screen-intent permission on API 34+), so launch the in-call screen
     * directly then — and also when notifications are disabled entirely, because the
     * incoming notification and its full-screen intent never appear at all then.
     * System-bound InCallServices are exempt from background-start limits,
     * and the singleTask/singleTop launch mode absorbs a duplicate start if the
     * full-screen intent fires too.
     */
    private fun shouldLaunchIncomingUiDirectly(): Boolean {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive != false
        val keyguardShowing = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        return !interactive || keyguardShowing || !canUseFullScreenIntentCompat() ||
            !callNotifications.incomingNotificationsUsable()
    }

    private fun canUseFullScreenIntentCompat(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() != false
    }
}
