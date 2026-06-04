package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.DropReward
import java.time.Instant
import java.time.temporal.ChronoUnit

object SampleTwitchData {
    fun campaigns(now: Instant = Instant.now()): List<Campaign> =
        listOf(
            Campaign(
                id = "sample-campaign-1",
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
                        id = "sample-drop-1",
                        name = "Explorer Badge",
                        currentMinutes = 30,
                        requiredMinutes = 30,
                        progress = 1f,
                        isClaimed = true,
                        canClaim = false,
                        rewards = listOf(DropReward("Explorer Badge", "BADGE")),
                    ),
                    CampaignDrop(
                        id = "sample-drop-2",
                        name = "Signal Emote",
                        currentMinutes = 18,
                        requiredMinutes = 60,
                        progress = 0.3f,
                        isClaimed = false,
                        canClaim = false,
                        rewards = listOf(DropReward("Signal Emote", "EMOTE")),
                        claimId = "sample-user#sample-campaign-1#sample-drop-2",
                    ),
                    CampaignDrop(
                        id = "sample-drop-3",
                        name = "Supply Crate",
                        currentMinutes = 0,
                        requiredMinutes = 120,
                        progress = 0f,
                        isClaimed = false,
                        canClaim = false,
                        rewards = listOf(DropReward("Supply Crate", "DIRECT_ENTITLEMENT")),
                        preconditionDropIds = listOf("sample-drop-2"),
                        claimId = "sample-user#sample-campaign-1#sample-drop-3",
                    ),
                ),
                allowedChannels = channels().take(1),
            ),
            Campaign(
                id = "sample-campaign-2",
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
                        id = "sample-drop-4",
                        name = "Cosmetic Pack",
                        currentMinutes = 0,
                        requiredMinutes = 90,
                        progress = 0f,
                        isClaimed = false,
                        canClaim = false,
                        rewards = listOf(DropReward("Cosmetic Pack", "DIRECT_ENTITLEMENT")),
                    ),
                ),
            ),
        )

    fun channels(gameName: String = "Sample Game"): List<Channel> =
        listOf(
            Channel(
                id = 101,
                name = "sample_channel",
                game = gameName,
                gameId = "999",
                viewers = 4200,
                online = true,
                dropsEnabled = true,
                aclBased = true,
                watching = true,
                broadcastId = "sample-broadcast-1",
                title = "Sample drops stream",
            ),
            Channel(
                id = 102,
                name = "backup_stream",
                game = gameName,
                gameId = "999",
                viewers = 980,
                online = true,
                dropsEnabled = true,
                broadcastId = "sample-broadcast-2",
                title = "Backup sample drops",
            ),
        )
}
