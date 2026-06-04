package com.nathan.twitchdropsminer.android.data.repository

import com.nathan.twitchdropsminer.android.data.backend.BackendClient
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.BackendConsole
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.runtime.MinerRuntimeReducer

class BackendMinerRepository(
    private val client: BackendClient,
) : MinerRepository {
    override suspend fun refresh(
        settings: AppSettings,
        previous: RuntimeSnapshot,
    ): RuntimeSnapshot {
        val baseUrl = settings.normalizedBackendUrl
        if (baseUrl.isBlank()) {
            return MinerRuntimeReducer.error("Backend URL is not configured.", previous)
        }

        return runCatching {
            val status = client.status(baseUrl)
            val campaigns = client.campaigns(baseUrl)
            val channels = client.channels(baseUrl)
            val console = runCatching { client.console(baseUrl) }
                .getOrDefault(BackendConsole())
            MinerRuntimeReducer.reduce(status, campaigns, channels, console)
        }.getOrElse { error ->
            MinerRuntimeReducer.error(
                message = error.message ?: "Unable to reach backend.",
                previous = previous,
            )
        }
    }

    override suspend fun reload(settings: AppSettings) {
        client.reload(settings.normalizedBackendUrl)
    }

    override suspend fun stopMiner(settings: AppSettings) {
        client.close(settings.normalizedBackendUrl)
    }

    override suspend fun selectChannel(settings: AppSettings, channelId: Long) {
        client.selectChannel(settings.normalizedBackendUrl, channelId)
    }
}
