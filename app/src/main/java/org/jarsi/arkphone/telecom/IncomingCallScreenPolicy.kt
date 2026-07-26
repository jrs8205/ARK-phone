package org.jarsi.arkphone.telecom

/**
 * Whether a ringing call should open ARK-phone's own call screen — always.
 *
 * Android turns a full-screen intent into a plain heads-up notification
 * while the device is unlocked, so the notification path alone leaves the
 * user with answer/decline buttons and no call screen. The device state is
 * still passed in so the tests can pin that none of these conditions may
 * silently take the call screen away again.
 */
@Suppress("UNUSED_PARAMETER")
internal fun shouldOpenIncomingCallScreen(
    interactive: Boolean,
    keyguardShowing: Boolean,
    fullScreenIntentAllowed: Boolean,
    incomingNotificationsUsable: Boolean,
): Boolean = true
