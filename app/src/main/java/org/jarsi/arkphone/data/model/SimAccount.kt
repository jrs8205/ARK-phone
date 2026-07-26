package org.jarsi.arkphone.data.model

/**
 * One call-capable SIM as Telecom sees it. [id] is the phone account's own
 * id, which is also what the call log stores per row and what an outgoing
 * call is addressed with, so it is the identity used everywhere SIMs appear.
 */
data class SimAccount(
    val id: String,
    val label: String,
    val number: String?,
) {
    val labelWithNumber: String get() = if (number.isNullOrBlank()) label else "$label ($number)"
}
