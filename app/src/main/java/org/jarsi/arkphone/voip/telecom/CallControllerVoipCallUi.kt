package org.jarsi.arkphone.voip.telecom

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.telecom.AudioControllerOwner
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallNotifications
import org.jarsi.arkphone.telecom.CallerAnnouncer
import org.jarsi.arkphone.ui.incall.InCallActivity
import org.jarsi.arkphone.voip.VoipForegroundService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes an ARK call through exactly the surfaces a carrier call uses: the
 * same CallController, the same notification and the same InCallActivity.
 * Unlike a carrier call, the ARK notification carries a full-screen intent —
 * a background-woken process has no other way to light a locked screen.
 */
@Singleton
class CallControllerVoipCallUi @Inject constructor(
    @ApplicationContext private val context: Context,
    private val callController: CallController,
    private val callNotifications: CallNotifications,
    private val callerAnnouncer: CallerAnnouncer,
    private val settingsCache: SettingsCache,
) : VoipCallUi {

    override fun added(handle: VoipCallHandle) {
        callNotifications.ensureChannels()
        callController.onCallAdded(handle)
    }

    override fun changed() = callController.onCallChanged()

    override fun removed(id: String) {
        // An ARK ring that ends unanswered must also stop mid-announcement.
        callerAnnouncer.onRingingStopped(id)
        callController.onCallRemoved(id)
    }

    override fun showIncoming(handle: VoipCallHandle) {
        val info = callController.calls.value.firstOrNull { it.id == handle.id }
        android.util.Log.i("ArkPhone", "ARK showIncoming id=${handle.id} found=${info != null}")
        if (info == null) return
        // The same announce contract as a ringing carrier call: the announcer
        // decides what to speak from settings, and voice-only mode silences
        // the channel ringtone under it — an ARK call used to ring the tone
        // over a setting that had turned it off (field-hit 2026-08-09 17:53).
        callerAnnouncer.onRinging(info)
        callNotifications.showIncomingCall(
            info,
            silentRing = settingsCache.current.announceMode == AnnounceMode.VOICE_ONLY,
            // With the app in the background, openCallScreen is a blocked
            // background launch — the heads-up banner is then the only
            // answer surface and must not be suppressed.
            arkBanner = !appInForeground(),
        )
    }

    private fun appInForeground(): Boolean {
        val state = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(state)
        return state.importance <=
            android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun showOngoing(handle: VoipCallHandle) {
        callController.calls.value.firstOrNull { it.id == handle.id }
            ?.let(callNotifications::showOngoingCall)
    }

    override fun silenceRinging(handle: VoipCallHandle) {
        callerAnnouncer.onRingingStopped(handle.id)
        callController.calls.value.firstOrNull { it.id == handle.id }
            ?.let(callNotifications::silenceRinging)
    }

    override fun clearNotification() = callNotifications.clear()

    override fun openCallScreen() {
        context.startActivity(InCallActivity.intent(context))
    }

    override fun startCallService() = VoipForegroundService.start(context)

    override fun stopCallService() = VoipForegroundService.stop(context)

    // The carrier path's controller is set by ArkInCallService, which never
    // binds for a self-managed ARK call — without this the mute and speaker
    // buttons act on null or on a stale carrier controller. Detaching by
    // owner hands the surface back to a still-active carrier call.
    override fun attachAudioControls(controller: org.jarsi.arkphone.telecom.InCallAudioController) {
        callController.attachAudioController(AudioControllerOwner.ARK, controller)
        callController.onAudioStateChanged(muted = false, speakerOn = false, earpiece = true)
    }

    override fun detachAudioControls() {
        callController.detachAudioController(AudioControllerOwner.ARK)
    }

    override fun audioStateChanged(muted: Boolean, speakerOn: Boolean) {
        // Off speaker, an ARK call plays through the earpiece — and the
        // earpiece flag is what arms the proximity screen-off. Left false, the
        // screen stayed lit against the ear (field-hit 2026-08-09 17:27).
        callController.onAudioStateChanged(
            muted = muted,
            speakerOn = speakerOn,
            earpiece = !speakerOn,
        )
    }
}
