package org.jarsi.arkphone.voip

/** What `POST /register` returns: the ARK code and the one-shot device token. */
data class ArkRegistration(val code: String, val deviceToken: String)

/** What `GET /account/<code>` returns. */
data class ArkAccount(val code: String, val nickname: String, val publicKey: String)

/**
 * Identity and directory operations. Bound only in builds that carry the VoIP
 * engine; a release build resolves `Optional.empty()` and every ARK surface
 * hides itself.
 */
interface VoipAccountGateway {
    /** Registers this device, persists the device token, returns null on failure. */
    suspend fun register(nickname: String): ArkRegistration?

    /** Directory lookup for a code the user typed; null when unknown. */
    suspend fun lookUp(code: String): ArkAccount?
}

/**
 * Outgoing-call handoff. [startCall] returns false when the engine cannot take
 * the call at all, in which case the caller MUST place a carrier call — VoIP
 * never prevents a phone call. When it returns true the engine owns the call
 * and invokes [onFallbackToCarrier] itself if the attempt fails before the
 * peer answers.
 */
interface VoipCallGateway {
    fun startCall(link: ArkLink, onFallbackToCarrier: () -> Unit): Boolean
}
