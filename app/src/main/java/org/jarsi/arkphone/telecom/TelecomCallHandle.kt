package org.jarsi.arkphone.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.DisconnectCause
import android.telecom.VideoProfile
import java.util.UUID

class TelecomCallHandle(private val call: Call) : CallHandle {

    override val id: String = UUID.randomUUID().toString()

    override val number: String?
        get() = call.details?.handle?.schemeSpecificPart

    override val displayName: String?
        get() {
            val contactName = if (Build.VERSION.SDK_INT >= 30) call.details?.contactDisplayName else null
            return contactName?.takeUnless(String::isBlank)
                ?: call.details?.callerDisplayName?.takeUnless(String::isBlank)
        }

    override val telecomState: Int
        get() = if (Build.VERSION.SDK_INT >= 31) {
            call.details?.state ?: -1
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

    override val connectTimeMillis: Long
        get() = call.details?.connectTimeMillis ?: 0

    override val simAccountId: String?
        get() = call.details?.accountHandle?.id

    override val disconnectError: DisconnectError?
        get() = call.details?.disconnectCause?.let { cause ->
            disconnectErrorOf(cause.code == DisconnectCause.ERROR, cause.label, cause.description)
        }

    override fun answer() = call.answer(VideoProfile.STATE_AUDIO_ONLY)
    override fun reject() = call.reject(false, null)
    override fun disconnect() = call.disconnect()
    override fun hold() = call.hold()
    override fun unhold() = call.unhold()
    override fun playDtmf(digit: Char) = call.playDtmfTone(digit)
    override fun stopDtmf() = call.stopDtmfTone()
}
