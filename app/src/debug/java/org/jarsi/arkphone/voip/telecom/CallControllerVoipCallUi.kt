package org.jarsi.arkphone.voip.telecom

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.telecom.CallController
import org.jarsi.arkphone.telecom.CallNotifications
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
) : VoipCallUi {

    override fun added(handle: VoipCallHandle) {
        callNotifications.ensureChannels()
        callController.onCallAdded(handle)
    }

    override fun changed() = callController.onCallChanged()

    override fun removed(id: String) = callController.onCallRemoved(id)

    override fun showIncoming(handle: VoipCallHandle) {
        val info = callController.calls.value.firstOrNull { it.id == handle.id }
        android.util.Log.i("ArkPhone", "ARK showIncoming id=${handle.id} found=${info != null}")
        info?.let(callNotifications::showIncomingCall)
    }

    override fun showOngoing(handle: VoipCallHandle) {
        callController.calls.value.firstOrNull { it.id == handle.id }
            ?.let(callNotifications::showOngoingCall)
    }

    override fun silenceRinging(handle: VoipCallHandle) {
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
    // buttons act on null or on a stale carrier controller.
    override fun attachAudioControls(controller: org.jarsi.arkphone.telecom.InCallAudioController) {
        callController.audioController = controller
        callController.onAudioStateChanged(muted = false, speakerOn = false, earpiece = true)
    }

    override fun detachAudioControls() {
        callController.audioController = null
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
