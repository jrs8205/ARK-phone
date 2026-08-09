package org.jarsi.arkphone.telecom

import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.voip.ArkLinkCache
import org.jarsi.arkphone.voip.VoipCallGateway
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one branch in the outgoing-call path. Every rejection lands on the same
 * answer — place the carrier call — because VoIP must never prevent a phone
 * call. Emergency numbers, USSD codes and every unlinked number take the
 * carrier path on the first check, with no network work and no added latency:
 * the link lookup is an in-memory map read.
 */
@Singleton
class CallRouter @Inject constructor(
    private val phoneCaller: PhoneCaller,
    private val settingsCache: SettingsCache,
    private val linkCache: ArkLinkCache,
    private val voipCallGateway: Optional<VoipCallGateway>,
    private val emergencyNumbers: EmergencyNumbers,
) {
    /** Returns false only when the call could not be placed at all. */
    fun placeCall(number: String): Boolean {
        if (number.isBlank()) return false
        // The contract in the class doc, made real: an emergency call or a
        // USSD/MMI string (* or # anywhere) never waits on the internet path,
        // even if a link key happens to collide with its digits.
        if (number.any { it == '*' || it == '#' } || emergencyNumbers.isEmergency(number)) {
            return phoneCaller.placeCall(number)
        }
        val gateway = voipCallGateway.orElse(null) ?: return phoneCaller.placeCall(number)
        if (!settingsCache.current.arkInternetCallsEnabled) return phoneCaller.placeCall(number)
        val link = linkCache.linkFor(number) ?: return phoneCaller.placeCall(number)
        // A bug anywhere in the engine must still leave the user with a call.
        val started = runCatching {
            gateway.startCall(link) { phoneCaller.placeCall(number) }
        }.getOrDefault(false)
        if (started) return true
        return phoneCaller.placeCall(number)
    }

    /** Voicemail is always the operator's; there is no internet equivalent. */
    fun placeVoicemailCall(): Boolean = phoneCaller.placeVoicemailCall()
}
