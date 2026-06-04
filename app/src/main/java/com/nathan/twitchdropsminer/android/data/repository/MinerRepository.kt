package com.nathan.twitchdropsminer.android.data.repository

import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot

interface MinerRepository {
    suspend fun refresh(
        settings: AppSettings,
        previous: RuntimeSnapshot = RuntimeSnapshot(),
    ): RuntimeSnapshot

    suspend fun reload(settings: AppSettings)

    suspend fun stopMiner(settings: AppSettings)

    suspend fun selectChannel(settings: AppSettings, channelId: Long)
}
