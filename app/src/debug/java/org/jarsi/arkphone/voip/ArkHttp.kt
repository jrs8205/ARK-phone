package org.jarsi.arkphone.voip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Where the signaling worker lives; supplied from local.properties. */
data class VoipConfig(val workerUrl: String)

data class ArkHttpResponse(val statusCode: Int, val body: String)

/** Boundary around OkHttp so the worker protocol is unit-testable. */
interface ArkHttp {
    /** Null means the request never completed — no status, no body. */
    suspend fun get(url: String, bearer: String?): ArkHttpResponse?

    suspend fun postJson(url: String, json: String, bearer: String?): ArkHttpResponse?
}

class OkHttpArkHttp(private val client: OkHttpClient) : ArkHttp {

    override suspend fun get(url: String, bearer: String?): ArkHttpResponse? =
        execute(Request.Builder().url(url).get(), bearer)

    override suspend fun postJson(url: String, json: String, bearer: String?): ArkHttpResponse? =
        execute(Request.Builder().url(url).post(json.toRequestBody(JSON)), bearer)

    private suspend fun execute(builder: Request.Builder, bearer: String?): ArkHttpResponse? =
        withContext(Dispatchers.IO) {
            try {
                bearer?.let { builder.header("Authorization", "Bearer $it") }
                client.newCall(builder.build()).execute().use { response ->
                    ArkHttpResponse(response.code, response.body.string())
                }
            } catch (_: Exception) {
                null
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
