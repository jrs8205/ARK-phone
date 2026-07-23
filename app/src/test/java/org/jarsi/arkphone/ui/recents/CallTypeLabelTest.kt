package org.jarsi.arkphone.ui.recents

import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Test

class CallTypeLabelTest {

    @Test
    fun otherGetsGenericLabel() {
        assertEquals(R.string.call_type_other, callTypeLabelRes(CallType.OTHER))
    }

    @Test
    fun specificTypesKeepTheirLabels() {
        assertEquals(R.string.call_type_incoming, callTypeLabelRes(CallType.INCOMING))
        assertEquals(R.string.call_type_outgoing, callTypeLabelRes(CallType.OUTGOING))
        assertEquals(R.string.call_type_missed, callTypeLabelRes(CallType.MISSED))
        assertEquals(R.string.call_type_rejected, callTypeLabelRes(CallType.REJECTED))
    }
}
