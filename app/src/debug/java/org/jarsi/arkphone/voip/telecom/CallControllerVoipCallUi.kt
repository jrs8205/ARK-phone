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
 * same CallController, the same notification (no CallStyle, no full-screen
 * intent — that decision is field-tested) and the same InCallActivity.
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

    override fun clearNotification() = callNotifications.clear()

    override fun openCallScreen() {
        context.startActivity(InCallActivity.intent(context))
    }

    override fun startCallService() = VoipForegroundService.start(context)

    override fun stopCallService() = VoipForegroundService.stop(context)
}
