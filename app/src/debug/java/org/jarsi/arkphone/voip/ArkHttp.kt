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
        execute(url, bearer) { it.get() }

    override suspend fun postJson(url: String, json: String, bearer: String?): ArkHttpResponse? =
        execute(url, bearer) { it.post(json.toRequestBody(JSON)) }

    private suspend fun execute(
        url: String,
        bearer: String?,
        configure: (Request.Builder) -> Request.Builder,
    ): ArkHttpResponse? = withContext(Dispatchers.IO) {
        try {
            // The URL parse lives INSIDE the boundary: a checkout without a
            // worker URL hands "" here, and that must be a null, not a crash.
            val builder = configure(Request.Builder().url(url))
            bearer?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { response ->
                ArkHttpResponse(response.code, response.body.string())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
