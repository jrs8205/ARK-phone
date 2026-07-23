package org.jarsi.arkphone.telecom

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Test

class CallStatusTest {

    @Test
    fun mapsTelecomStates() {
        assertEquals(CallStatus.RINGING, mapTelecomState(Call.STATE_RINGING))
        assertEquals(CallStatus.DIALING, mapTelecomState(Call.STATE_DIALING))
        assertEquals(CallStatus.DIALING, mapTelecomState(Call.STATE_CONNECTING))
        assertEquals(CallStatus.ACTIVE, mapTelecomState(Call.STATE_ACTIVE))
        assertEquals(CallStatus.HOLDING, mapTelecomState(Call.STATE_HOLDING))
        assertEquals(CallStatus.DISCONNECTING, mapTelecomState(Call.STATE_DISCONNECTING))
        assertEquals(CallStatus.DISCONNECTED, mapTelecomState(Call.STATE_DISCONNECTED))
        assertEquals(CallStatus.UNKNOWN, mapTelecomState(-1))
    }
}
