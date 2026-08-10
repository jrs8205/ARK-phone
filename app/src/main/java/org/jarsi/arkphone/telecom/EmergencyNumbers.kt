package org.jarsi.arkphone.telecom

/** Answers whether a dial string is an emergency number on this device. */
fun interface EmergencyNumbers {
    fun isEmergency(number: String): Boolean
}

/**
 * Classification failure fails toward the carrier: a linked 112 must never
 * enter the ARK path because telephony was unavailable — a misrouted ordinary
 * call still connects over the carrier, the reverse strands an emergency call
 * behind VoIP setup.
 */
class CarrierBiasedEmergencyNumbers(
    private val platformCheck: (String) -> Boolean,
) : EmergencyNumbers {
    override fun isEmergency(number: String): Boolean =
        runCatching { platformCheck(number) }.getOrDefault(true)
}

/** The API 34+ full-screen-intent app op, behind a JVM-testable seam. */
fun interface FullScreenIntentPermission {
    fun allowed(): Boolean
}
