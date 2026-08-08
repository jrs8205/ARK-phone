package org.jarsi.arkphone.voip

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * The worker's HTTP surface, exactly as worker/docs/protocol.md describes it.
 * Every failure is a null or false — this layer never throws at its callers,
 * because any uncertainty on the VoIP path has to degrade to a carrier call.
 */
class ArkAccountClient(
    private val http: ArkHttp,
    private val workerUrl: String,
) {
    /** `POST /register`, the only unauthenticated route. */
    suspend fun register(
        nickname: String,
        publicKey: String,
        fcmToken: String?,
    ): ArkRegistration? {
        val body = buildJsonObject {
            put("nickname", nickname)
            put("publicKey", publicKey)
            // A device registered with an empty token can never be woken, and
            // the worker accepts "" — so omit the field instead of sending one.
            if (!fcmToken.isNullOrBlank()) put("fcmToken", fcmToken)
        }
        val response = http.postJson("$workerUrl/register", body.toString(), bearer = null)
        if (response == null || response.statusCode != OK) return null
        val json = runCatching {
            SignalingJson.json.parseToJsonElement(response.body).jsonObject
        }.getOrNull() ?: return null
        val code = (json["code"] as? JsonPrimitive)?.content ?: return null
        val deviceToken = (json["deviceToken"] as? JsonPrimitive)?.content ?: return null
        return ArkRegistration(code, deviceToken)
    }

    /** `GET /account/<code>` — the only way to prove an account exists. */
    suspend fun lookUp(code: String, bearer: String): ArkAccount? {
        val response = http.get("$workerUrl/account/$code", bearer)
        if (response == null || response.statusCode != OK) return null
        val json = runCatching {
            SignalingJson.json.parseToJsonElement(response.body).jsonObject
        }.getOrNull() ?: return null
        return ArkAccount(
            code = (json["code"] as? JsonPrimitive)?.content ?: return null,
            nickname = (json["nickname"] as? JsonPrimitive)?.content ?: return null,
            publicKey = (json["publicKey"] as? JsonPrimitive)?.content ?: return null,
        )
    }

    /** `POST /account/fcm-token` — 204 on success; there is no clear route. */
    suspend fun updateFcmToken(fcmToken: String, bearer: String): Boolean {
        if (fcmToken.isBlank()) return false
        val body = buildJsonObject { put("fcmToken", fcmToken) }
        val response = http.postJson("$workerUrl/account/fcm-token", body.toString(), bearer)
        return response?.statusCode == NO_CONTENT
    }

    /** `GET /turn-credentials` — short-lived, so fetched per call attempt. */
    suspend fun turnCredentials(bearer: String): List<IceServerConfig>? {
        val response = http.get("$workerUrl/turn-credentials", bearer)
        if (response == null || response.statusCode != OK) return null
        return TurnCredentialsParser.parse(response.body)
    }

    private companion object {
        const val OK = 200
        const val NO_CONTENT = 204
    }
}
