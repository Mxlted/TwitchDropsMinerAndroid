package com.nathan.twitchdropsminer.android.data.backend

import com.nathan.twitchdropsminer.android.data.model.BackendConsole
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.MinerStatus
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val JsonMediaType = "application/json; charset=utf-8".toMediaType()

class BackendClient(
    private val httpClient: OkHttpClient,
    private val mapper: BackendMappers = BackendMappers(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun status(baseUrl: String): MinerStatus =
        mapper.parseStatus(get(baseUrl, "/api/status"))

    suspend fun campaigns(baseUrl: String): List<Campaign> =
        mapper.parseCampaigns(get(baseUrl, "/api/campaigns"))

    suspend fun channels(baseUrl: String): List<Channel> =
        mapper.parseChannels(get(baseUrl, "/api/channels"))

    suspend fun console(baseUrl: String): BackendConsole =
        mapper.parseConsole(get(baseUrl, "/api/console"))

    suspend fun reload(baseUrl: String) {
        post(baseUrl, "/api/reload", "")
    }

    suspend fun close(baseUrl: String) {
        post(baseUrl, "/api/close", "")
    }

    suspend fun selectChannel(baseUrl: String, channelId: Long) {
        post(baseUrl, "/api/channels/select", """{"channel_id":$channelId}""")
    }

    private suspend fun get(baseUrl: String, path: String): String =
        execute(
            Request.Builder()
                .url("${normalize(baseUrl)}$path")
                .get()
                .build(),
        )

    private suspend fun post(baseUrl: String, path: String, body: String): String =
        execute(
            Request.Builder()
                .url("${normalize(baseUrl)}$path")
                .post(body.toRequestBody(JsonMediaType))
                .build(),
        )

    private suspend fun execute(request: Request): String = withContext(ioDispatcher) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw BackendException("Backend returned HTTP ${response.code}: ${body.take(160)}")
            }
            body
        }
    }

    private fun normalize(baseUrl: String): String =
        BackendUrlValidator.normalize(baseUrl).getOrElse { throw BackendException(it.message) }
}

class BackendException(message: String?) : IOException(message ?: "Backend request failed")
