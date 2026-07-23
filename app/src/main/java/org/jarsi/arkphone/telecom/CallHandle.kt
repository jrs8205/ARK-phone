package org.jarsi.arkphone.telecom

interface CallHandle {
    val id: String
    val number: String?
    val displayName: String?
    val telecomState: Int
    val connectTimeMillis: Long
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
)

data class CallAudioUiState(
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
)

interface InCallAudioController {
    fun applyMuted(muted: Boolean)
    fun applyRoute(speaker: Boolean)
}
