package org.jarsi.arkphone.telecom

/** Answers whether a dial string is an emergency number on this device. */
fun interface EmergencyNumbers {
    fun isEmergency(number: String): Boolean
}

/** The API 34+ full-screen-intent app op, behind a JVM-testable seam. */
fun interface FullScreenIntentPermission {
    fun allowed(): Boolean
}
