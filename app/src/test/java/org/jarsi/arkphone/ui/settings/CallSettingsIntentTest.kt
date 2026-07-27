package org.jarsi.arkphone.ui.settings

import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CallSettingsIntentTest {

    @Test
    fun carriesTheDefaultVoiceSubscription() {
        // Without the subscription the system screen queries legacy
        // "phone 0" — the empty slot on a single-eSIM device — and every
        // query dies with "Network or SIM card error".
        val intent = callSettingsIntent(defaultVoiceSubscriptionId = 1)
        assertEquals(TelecomManager.ACTION_SHOW_CALL_SETTINGS, intent.action)
        assertEquals(1, intent.getIntExtra(CALL_SETTINGS_SUB_ID_EXTRA, -99))
    }

    @Test
    fun omitsAnInvalidSubscription() {
        val intent = callSettingsIntent(
            defaultVoiceSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        )
        assertEquals(TelecomManager.ACTION_SHOW_CALL_SETTINGS, intent.action)
        assertFalse(intent.hasExtra(CALL_SETTINGS_SUB_ID_EXTRA))
    }
}
