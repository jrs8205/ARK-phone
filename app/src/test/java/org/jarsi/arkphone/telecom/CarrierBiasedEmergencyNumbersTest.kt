package org.jarsi.arkphone.telecom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierBiasedEmergencyNumbersTest {

    @Test
    fun passesThePlatformVerdictThroughWhenItAnswers() {
        assertTrue(CarrierBiasedEmergencyNumbers { true }.isEmergency("112"))
        assertFalse(CarrierBiasedEmergencyNumbers { false }.isEmergency("0401234567"))
    }

    @Test
    fun classifiesAsEmergencyWhenThePlatformCheckThrows() {
        // A linked 112 must never enter the ARK path because telephony was
        // unavailable; a misrouted ordinary call still connects over the
        // carrier, the reverse strands an emergency call behind VoIP setup.
        val numbers = CarrierBiasedEmergencyNumbers { throw IllegalStateException("no telephony") }
        assertTrue(numbers.isEmergency("112"))
    }
}
