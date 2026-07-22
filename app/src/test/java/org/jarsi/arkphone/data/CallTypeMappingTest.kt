package org.jarsi.arkphone.data

import android.provider.CallLog
import org.jarsi.arkphone.data.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Test

class CallTypeMappingTest {

    @Test
    fun mapsKnownTypes() {
        assertEquals(CallType.INCOMING, callTypeFrom(CallLog.Calls.INCOMING_TYPE))
        assertEquals(CallType.OUTGOING, callTypeFrom(CallLog.Calls.OUTGOING_TYPE))
        assertEquals(CallType.MISSED, callTypeFrom(CallLog.Calls.MISSED_TYPE))
        assertEquals(CallType.REJECTED, callTypeFrom(CallLog.Calls.REJECTED_TYPE))
    }

    @Test
    fun mapsUnknownTypesToOther() {
        assertEquals(CallType.OTHER, callTypeFrom(99))
    }
}
