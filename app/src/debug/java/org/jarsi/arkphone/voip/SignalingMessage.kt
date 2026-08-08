package org.jarsi.arkphone.voip

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Wire envelope shared with the signaling worker; see worker/src/inbox.ts. */
@Serializable
data class SignalingMessage(
    val type: String,
    val to: String? = null,
    val from: String? = null,
    val payload: JsonObject? = null,
)

object SignalingTypes {
    const val REACH_QUERY = "reach-query"
    const val REACH_REPLY = "reach-reply"
    const val CALL_OFFER = "call-offer"
    const val CALL_ANSWER = "call-answer"
    const val CALL_REJECT = "call-reject"
    const val ICE_CANDIDATE = "ice-candidate"
    const val CALL_END = "call-end"
    const val ERROR = "error"
}

object SignalingJson {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(message: SignalingMessage): String = json.encodeToString(SignalingMessage.serializer(), message)

    fun decode(text: String): SignalingMessage? = try {
        json.decodeFromString(SignalingMessage.serializer(), text)
    } catch (_: Exception) {
        null
    }
}
