package org.jarsi.arkphone.voip.telecom

import org.jarsi.arkphone.telecom.InCallAudioController

/**
 * Platform registration for a self-managed call: audio focus, Bluetooth and
 * speaker routing, volume keys and coexistence with carrier calls all come
 * from Telecom once the call is added.
 */
interface VoipTelecom {
    /**
     * False when the platform refuses the call outright — the caller must not
     * proceed. Registration is asynchronous under the hood, so a later refusal
     * arrives through [onFailed] instead of the return value.
     */
    fun add(
        handle: VoipCallHandle,
        onSystemAnswer: () -> Unit,
        onSystemDisconnect: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean

    /** The user answered in ARK's own UI; the platform must hear it too. */
    fun answered(id: String)

    fun setActive(id: String)

    /** Routes audio to the speaker or back to the earpiece via Telecom. */
    fun requestSpeaker(id: String, speakerOn: Boolean)

    fun remove(id: String)
}

/** What the coordinator needs from ARK's existing call surfaces. */
interface VoipCallUi {
    fun added(handle: VoipCallHandle)
    fun changed()
    fun removed(id: String)
    fun showIncoming(handle: VoipCallHandle)
    fun showOngoing(handle: VoipCallHandle)
    fun clearNotification()
    fun openCallScreen()
    fun startCallService()
    fun stopCallService()

    /** Mounts this call's mute/speaker handler behind the in-call buttons. */
    fun attachAudioControls(controller: InCallAudioController)
    fun detachAudioControls()
    fun audioStateChanged(muted: Boolean, speakerOn: Boolean)
}
