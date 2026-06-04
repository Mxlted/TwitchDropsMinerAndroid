package com.nathan.twitchdropsminer.android.data.repository

import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.BackendConsole
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.DropReward
import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.MinerStatus
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.runtime.MinerRuntimeReducer
import java.time.Instant
import java.time.temporal.ChronoUnit

class SampleMinerRepository : MinerRepository {
    override suspend fun refresh(
        settings: AppSettings,
        previous: RuntimeSnapshot,
    ): RuntimeSnapshot {
        val now = Instant.now()
        val campaigns = sampleCampaigns(now)
        val channels = sampleChannels()
        val status = MinerStatus(
            statusText = "Watching sample_channel",
            login = LoginSession(
                state = LoginState.LoggedIn,
                statusText = "Logged in as sample user",
                userId = "123456",
            ),
            manualMode = false,
            dropsClaimedThisSession = 1,
        )
        return MinerRuntimeReducer.reduce(
            status = status,
            campaigns = campaigns,
            channels = channels,
            console = BackendConsole(
                lines = listOf(
                    "[sample] Inventory loaded",
                    "[sample] Watching sample_channel",
                ),
            ),
            now = now,
        )
    }

    override suspend fun reload(settings: AppSettings) = Unit

    override suspend fun stopMiner(settings: AppSettings) = Unit

    override suspend fun selectChannel(settings: AppSettings, channelId: Long) = Unit

    private fun sampleCampaigns(now: Instant): List<Campaign> =
        listOf(
            Campaign(
                id = "sample-1",
                name = "Community Celebration Drops",
                gameName = "Sample Game",
                startsAt = now.minus(2, ChronoUnit.HOURS),
                endsAt = now.plus(8, ChronoUnit.HOURS),
                linked = true,
                active = true,
                claimedDrops = 1,
                totalDrops = 3,
                drops = listOf(
                    CampaignDrop(
                        id = "drop-1",
                        name = "Explorer Badge",
                        currentMinutes = 30,
                        requiredMinutes = 30,
                        progress = 1f,
                        isClaimed = true,
                        canClaim = false,
                        rewards = listOf(DropReward("Explorer Badge", "BADGE")),
                    ),
                    CampaignDrop(
                        id = "drop-2",
                        name = "Signal Emote",
                        currentMinutes = 18,
                        requiredMinutes = 60,
                        progress = 0.3f,
                        isClaimed = false,
                        canClaim = false,
                        rewards = listOf(DropReward("Signal Emote", "EMOTE")),
                    ),
                ),
            ),
            Campaign(
                id = "sample-2",
                name = "Weekend Gear",
                gameName = "Another Game",
                startsAt = now.plus(1, ChronoUnit.HOURS),
                endsAt = now.plus(12, ChronoUnit.HOURS),
                linked = false,
                upcoming = true,
                claimedDrops = 0,
                totalDrops = 1,
                drops = listOf(
                    CampaignDrop(
                        id = "drop-3",
                        name = "Supply Crate",
                        currentMinutes = 0,
                        requiredMinutes = 120,
                        progress = 0f,
                        isClaimed = false,
                        canClaim = false,
                        rewards = listOf(DropReward("Supply Crate", "DIRECT_ENTITLEMENT")),
                    ),
                ),
            ),
        )

    private fun sampleChannels(): List<Channel> =
        listOf(
            Channel(
                id = 101,
                name = "sample_channel",
                game = "Sample Game",
                viewers = 4200,
                online = true,
                dropsEnabled = true,
                aclBased = true,
                watching = true,
            ),
            Channel(
                id = 102,
                name = "backup_stream",
                game = "Sample Game",
                viewers = 980,
                online = true,
                dropsEnabled = true,
            ),
        )
}
