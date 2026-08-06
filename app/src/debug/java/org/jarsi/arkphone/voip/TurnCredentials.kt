package org.jarsi.arkphone.voip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

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

class TurnCredentialsFetcher(
    private val client: OkHttpClient,
    private val workerUrl: String,
    private val authToken: String,
) {
    suspend fun fetch(): List<IceServerConfig>? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$workerUrl/turn-credentials")
                .header("Authorization", "Bearer $authToken")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                TurnCredentialsParser.parse(response.body.string())
            }
        } catch (_: Exception) {
            null
        }
    }
}
