package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.local.BoundedLogBuffer
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.BackendConsole
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.MinerStatus
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.runtime.CampaignCandidateDecision
import com.nathan.twitchdropsminer.android.runtime.CampaignPrioritySelector
import com.nathan.twitchdropsminer.android.runtime.CampaignSelectionMode
import com.nathan.twitchdropsminer.android.runtime.MinerRuntimeReducer
import com.nathan.twitchdropsminer.android.runtime.SampleTwitchData
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeStateTest {
    @Test
    fun watchingChannelWinsRuntimePhase() {
        val snapshot = MinerRuntimeReducer.reduce(
            status = MinerStatus(
                statusText = "Idle",
                login = LoginSession(LoginState.LoggedIn, "Logged in"),
            ),
            campaigns = listOf(Campaign(id = "c1", name = "Campaign", gameName = "Game", active = true, totalDrops = 1)),
            channels = listOf(Channel(id = 1, name = "streamer", online = true, dropsEnabled = true, watching = true)),
            console = BackendConsole(),
            now = Instant.parse("2026-06-03T00:00:00Z"),
        )

        assertEquals(RuntimePhase.Watching, snapshot.phase)
        assertEquals("Watching streamer", snapshot.currentTask)
        assertEquals("1 active campaigns, 0/1 drops claimed (0%)", snapshot.progressSummary)
    }

    @Test
    fun errorStatePreservesPreviousData() {
        val previous = MinerRuntimeReducer.reduce(
            status = MinerStatus("Watching streamer", LoginSession(LoginState.LoggedIn, "Logged in")),
            campaigns = listOf(Campaign(id = "c1", name = "Campaign", gameName = "Game")),
            channels = emptyList(),
            console = BackendConsole(),
        )

        val errored = MinerRuntimeReducer.error("No internet", previous)

        assertEquals(RuntimePhase.Error, errored.phase)
        assertEquals(1, errored.campaigns.size)
        assertEquals("No internet", errored.error)
    }

    @Test
    fun boundedLogsKeepNewestEntries() {
        assertEquals(listOf(3, 4, 5), BoundedLogBuffer.trim(listOf(1, 2, 3, 4, 5), 3))
    }

    @Test
    fun boundedLogsTreatNonPositiveLimitAsEmpty() {
        assertEquals(emptyList<Int>(), BoundedLogBuffer.trim(listOf(1, 2, 3), 0))
    }

    @Test
    fun sampleCampaignsExposeEarnableLocalWork() {
        val campaign = SampleTwitchData.campaigns().first()

        assertEquals(true, campaign.canEarnLocally)
        assertEquals("Sample Game", campaign.gameName)
        assertEquals(3, campaign.totalDrops)
    }

    @Test
    fun gamePrioritySelectsFirstEarnableCampaignForHighestPriorityGame() {
        val lowerPriority = earnableCampaign("campaign-1", "Lower Priority")
        val higherPriority = earnableCampaign("campaign-2", "Higher Priority")
        val settings = AppSettings(
            selectedGamePriority = listOf("Higher Priority", "Lower Priority"),
        ).normalized()

        val selected = CampaignPrioritySelector.select(settings, listOf(lowerPriority, higherPriority))

        assertEquals("campaign-2", selected?.id)
    }

    @Test
    fun gamePriorityCandidatesKeepLowerPriorityGamesAsFallbacks() {
        val firstPriority = earnableCampaign("campaign-1", "First Priority")
        val secondPriority = earnableCampaign("campaign-2", "Second Priority")
        val unselected = earnableCampaign("campaign-3", "Unselected")
        val settings = AppSettings(
            selectedGamePriority = listOf("First Priority", "Second Priority"),
        ).normalized()

        val candidates = CampaignPrioritySelector.candidates(
            settings,
            listOf(secondPriority, unselected, firstPriority),
        )

        assertEquals(listOf("campaign-1", "campaign-2"), candidates.map { it.id })
    }

    @Test
    fun gamePriorityDoesNotFallBackToUnselectedGames() {
        val campaign = earnableCampaign("campaign-1", "Unselected Game")
        val settings = AppSettings(selectedGamePriority = listOf("Selected Game")).normalized()

        assertNull(CampaignPrioritySelector.select(settings, listOf(campaign)))
    }

    @Test
    fun autoModeSelectsFirstEarnableCampaign() {
        val campaign = earnableCampaign("campaign-1", "Any Game")

        val selected = CampaignPrioritySelector.select(AppSettings(), listOf(campaign))

        assertEquals("campaign-1", selected?.id)
    }

    @Test
    fun completePrioritizedGamesFallBackToAutoOnlyWhenEnabled() {
        val complete = completedCampaign("campaign-1", "Selected Game")
        val other = earnableCampaign("campaign-2", "Other Game")
        val disabled = AppSettings(
            selectedGamePriority = listOf("Selected Game"),
        ).normalized()
        val enabled = disabled.copy(fallbackToAutoWhenPrioritizedComplete = true).normalized()

        val disabledDecision = CampaignPrioritySelector.initialDecision(disabled, listOf(complete, other))
        val enabledDecision = CampaignPrioritySelector.initialDecision(enabled, listOf(complete, other))

        assertEquals("All prioritized games are complete", (disabledDecision as CampaignCandidateDecision.Idle).task)
        assertEquals(
            CampaignSelectionMode.AutoFallbackPrioritiesComplete,
            (enabledDecision as CampaignCandidateDecision.Try).mode,
        )
        assertEquals(listOf("campaign-2"), enabledDecision.candidates.map { it.id })
    }

    @Test
    fun noChannelPrioritizedGamesFallBackToAutoOnlyWhenEnabled() {
        val prioritized = earnableCampaign("campaign-1", "Selected Game")
        val other = earnableCampaign("campaign-2", "Other Game")
        val disabled = AppSettings(
            selectedGamePriority = listOf("Selected Game"),
        ).normalized()
        val enabled = disabled.copy(fallbackToAutoWhenNoPrioritizedChannel = true).normalized()

        val disabledDecision = CampaignPrioritySelector.afterNoPrioritizedChannelDecision(
            disabled,
            listOf(prioritized, other),
        )
        val enabledDecision = CampaignPrioritySelector.afterNoPrioritizedChannelDecision(
            enabled,
            listOf(prioritized, other),
        )

        assertEquals(
            "No prioritized games have eligible live channels",
            (disabledDecision as CampaignCandidateDecision.Idle).task,
        )
        assertEquals(
            "Auto Mode fallback for prioritized games without live channels is disabled.",
            disabledDecision.detail,
        )
        assertEquals(
            CampaignSelectionMode.AutoFallbackNoPrioritizedChannel,
            (enabledDecision as CampaignCandidateDecision.Try).mode,
        )
        assertEquals(listOf("campaign-2"), enabledDecision.candidates.map { it.id })
    }

    @Test
    fun autoModeDoesNotUseSampleDataUnlessExplicitlyEnabled() {
        val settings = AppSettings().normalized()
        val decision = CampaignPrioritySelector.initialDecision(settings, emptyList())

        assertEquals(false, settings.useSampleDataFallback)
        assertEquals(false, settings.sampleMode)
        assertEquals("No available campaign can be mined", (decision as CampaignCandidateDecision.Idle).task)
    }

    @Test
    fun claimableDropsCountAsEarnableLocalWork() {
        val campaign = earnableCampaign(
            id = "campaign-1",
            gameName = "Game",
            currentMinutes = 60,
            requiredMinutes = 60,
            canClaim = true,
        )

        assertEquals(true, campaign.canEarnLocally)
    }

    @Test
    fun completedUnclaimedDropsCountAsEarnableLocalWork() {
        val campaign = earnableCampaign(
            id = "campaign-1",
            gameName = "Game",
            currentMinutes = 60,
            requiredMinutes = 60,
            canClaim = false,
        )

        assertEquals(true, campaign.canEarnLocally)
    }
}

private fun completedCampaign(id: String, gameName: String): Campaign =
    Campaign(
        id = id,
        name = "$gameName Drops",
        gameName = gameName,
        linked = true,
        active = true,
        totalDrops = 1,
        claimedDrops = 1,
        drops = listOf(
            CampaignDrop(
                id = "$id-drop",
                name = "Drop",
                currentMinutes = 60,
                requiredMinutes = 60,
                progress = 1f,
                isClaimed = true,
                canClaim = false,
                rewards = emptyList(),
            ),
        ),
    )

private fun earnableCampaign(
    id: String,
    gameName: String,
    currentMinutes: Int = 0,
    requiredMinutes: Int = 60,
    canClaim: Boolean = false,
): Campaign =
    Campaign(
        id = id,
        name = "$gameName Drops",
        gameName = gameName,
        linked = true,
        active = true,
        totalDrops = 1,
        claimedDrops = 0,
        drops = listOf(
            CampaignDrop(
                id = "$id-drop",
                name = "Drop",
                currentMinutes = currentMinutes,
                requiredMinutes = requiredMinutes,
                progress = if (requiredMinutes <= 0) 0f else currentMinutes.toFloat() / requiredMinutes,
                isClaimed = false,
                canClaim = canClaim,
                rewards = emptyList(),
            ),
        ),
    )
