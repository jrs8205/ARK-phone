package org.jarsi.arkphone.telecom

import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallScreenPolicyTest {

    @Test
    fun theCallScreenOpensWhileTheDeviceIsUnlockedAndInUse() {
        // Android downgrades a full-screen intent to a heads-up notification
        // whenever the device is unlocked, so relying on it left the user
        // with only the notification's answer/decline buttons.
        assertTrue(
            shouldOpenIncomingCallScreen(
                interactive = true,
                keyguardShowing = false,
                fullScreenIntentAllowed = true,
                incomingNotificationsUsable = true,
            ),
        )
    }

    @Test
    fun theCallScreenOpensOnTheLockScreen() {
        assertTrue(
            shouldOpenIncomingCallScreen(
                interactive = false,
                keyguardShowing = true,
                fullScreenIntentAllowed = true,
                incomingNotificationsUsable = true,
            ),
        )
    }

    @Test
    fun theCallScreenOpensWithoutFullScreenIntentPermission() {
        assertTrue(
            shouldOpenIncomingCallScreen(
                interactive = true,
                keyguardShowing = false,
                fullScreenIntentAllowed = false,
                incomingNotificationsUsable = true,
            ),
        )
    }

    @Test
    fun theCallScreenOpensWhenNotificationsCannotBePosted() {
        assertTrue(
            shouldOpenIncomingCallScreen(
                interactive = true,
                keyguardShowing = false,
                fullScreenIntentAllowed = true,
                incomingNotificationsUsable = false,
            ),
        )
    }
}
