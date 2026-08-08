package org.jarsi.arkphone.voip

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

object TurnCredentialsParser {
    fun parse(body: String): List<IceServerConfig>? = try {
        val root = SignalingJson.json.parseToJsonElement(body).jsonObject
        root["iceServers"]!!.jsonArray.map { element ->
            val server = element.jsonObject
            val urls = when (val u = server["urls"]) {
                is JsonArray -> u.map { it.jsonPrimitive.content }
                is JsonPrimitive -> listOf(u.content)
                else -> emptyList()
            }
            IceServerConfig(
                urls = urls,
                username = (server["username"] as? JsonPrimitive)?.content,
                credential = (server["credential"] as? JsonPrimitive)?.content,
            )
        }
    } catch (_: Exception) {
        null
    }
}
