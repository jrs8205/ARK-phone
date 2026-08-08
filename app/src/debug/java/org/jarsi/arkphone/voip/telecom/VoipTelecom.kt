package org.jarsi.arkphone.voip.telecom

/**
 * Platform registration for a self-managed call: audio focus, Bluetooth and
 * speaker routing, volume keys and coexistence with carrier calls all come
 * from Telecom once the call is added.
 */
interface VoipTelecom {
    /** False when the platform refuses the call — the caller must not proceed. */
    fun add(
        handle: VoipCallHandle,
        onSystemAnswer: () -> Unit,
        onSystemDisconnect: () -> Unit,
    ): Boolean

    fun setActive(id: String)

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
}
