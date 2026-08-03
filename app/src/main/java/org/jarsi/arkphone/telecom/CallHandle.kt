package org.jarsi.arkphone.telecom

/** Telephony's localized explanation for a call that ended in error;
 *  at least one field is non-null. */
data class DisconnectError(val label: String?, val description: String?)

/** Null unless the cause is an actual error carrying displayable text —
 *  normal hangups and silent errors produce nothing to show. */
fun disconnectErrorOf(
    isError: Boolean,
    label: CharSequence?,
    description: CharSequence?,
): DisconnectError? {
    if (!isError) return null
    val labelText = label?.toString()?.takeUnless(String::isBlank)
    val descriptionText = description?.toString()?.takeUnless(String::isBlank)
    if (labelText == null && descriptionText == null) return null
    return DisconnectError(labelText, descriptionText)
}

interface CallHandle {
    val id: String
    val number: String?
    val displayName: String?
    val telecomState: Int
    val connectTimeMillis: Long

    /** Phone account id the call uses, i.e. which SIM carries it. */
    val simAccountId: String?

    /** Why a failed call ended, from the platform; null for normal ends. */
    val disconnectError: DisconnectError? get() = null
    fun answer()
    fun reject()
    fun disconnect()
    fun hold()
    fun unhold()
    fun playDtmf(digit: Char)
    fun stopDtmf()
}

data class CallInfo(
    val id: String,
    val number: String?,
    val displayName: String?,
    val status: CallStatus,
    val connectedAtMillis: Long?,
    val simAccountId: String? = null,
    val disconnectError: DisconnectError? = null,
)

data class CallAudioUiState(
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val earpiece: Boolean = false,
)

interface InCallAudioController {
    fun applyMuted(muted: Boolean)
    fun applyRoute(speaker: Boolean)
}
