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
import com.nathan.twitchdropsminer.android.runtime.ActiveWatchGuard
import com.nathan.twitchdropsminer.android.runtime.CampaignCandidateDecision
import com.nathan.twitchdropsminer.android.runtime.CampaignPrioritySelector
import com.nathan.twitchdropsminer.android.runtime.CampaignSelectionMode
import com.nathan.twitchdropsminer.android.runtime.MinerRuntimeReducer
import com.nathan.twitchdropsminer.android.runtime.SampleTwitchData
import com.nathan.twitchdropsminer.android.runtime.UnlinkedProgressProbe
import com.nathan.twitchdropsminer.android.runtime.UnlinkedProgressProbeResult
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun gamePrioritySkipsExcludedCampaignsBeforeLowerPriorityFallback() {
        val excludedHigherPriority = earnableCampaign("campaign-1", "Higher Priority")
        val lowerPriority = earnableCampaign("campaign-2", "Lower Priority")
        val settings = AppSettings(
            selectedGamePriority = listOf("Higher Priority", "Lower Priority"),
            excludedCampaignIds = setOf("campaign-1"),
        ).normalized()

        val selected = CampaignPrioritySelector.select(
            settings,
            listOf(excludedHigherPriority, lowerPriority),
        )

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
    fun gamePriorityChecksLowerPriorityWorkBeforeCompleteFallback() {
        val completeFirstPriority = completedCampaign("campaign-1", "First Priority")
        val earnableSecondPriority = earnableCampaign("campaign-2", "Second Priority")
        val unselected = earnableCampaign("campaign-3", "Unselected")
        val settings = AppSettings(
            selectedGamePriority = listOf("First Priority", "Second Priority"),
            fallbackToAutoWhenPrioritizedComplete = true,
        ).normalized()

        val decision = CampaignPrioritySelector.initialDecision(
            settings,
            listOf(completeFirstPriority, unselected, earnableSecondPriority),
        )

        assertEquals(CampaignSelectionMode.Prioritized, (decision as CampaignCandidateDecision.Try).mode)
        assertEquals(listOf("campaign-2"), decision.candidates.map { it.id })
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
    fun autoModeSkipsExcludedCampaigns() {
        val excluded = earnableCampaign("campaign-1", "Excluded Game")
        val included = earnableCampaign("campaign-2", "Included Game")
        val settings = AppSettings(excludedCampaignIds = setOf("campaign-1")).normalized()

        val decision = CampaignPrioritySelector.initialDecision(settings, listOf(excluded, included))

        assertEquals(CampaignSelectionMode.Auto, (decision as CampaignCandidateDecision.Try).mode)
        assertEquals(listOf("campaign-2"), decision.candidates.map { it.id })
    }

    @Test
    fun activeWatchStopsWhenCurrentCampaignBecomesExcluded() {
        val campaign = earnableCampaign("campaign-1", "Active Game")
        val included = AppSettings().normalized()
        val excluded = AppSettings(excludedCampaignIds = setOf("CAMPAIGN-1")).normalized()

        assertFalse(ActiveWatchGuard.shouldStopForExcludedCampaign(included, campaign))
        assertTrue(ActiveWatchGuard.shouldStopForExcludedCampaign(excluded, campaign))
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
    fun completePriorityFallbackSkipsExcludedCampaigns() {
        val complete = completedCampaign("campaign-1", "Selected Game")
        val excludedOther = earnableCampaign("campaign-2", "Other Game")
        val settings = AppSettings(
            selectedGamePriority = listOf("Selected Game"),
            fallbackToAutoWhenPrioritizedComplete = true,
            excludedCampaignIds = setOf("campaign-2"),
        ).normalized()

        val decision = CampaignPrioritySelector.initialDecision(settings, listOf(complete, excludedOther))

        assertEquals("All prioritized games are complete", (decision as CampaignCandidateDecision.Idle).task)
        assertEquals(
            "Auto Mode fallback is enabled, but no other eligible campaign is available.",
            decision.detail,
        )
    }

    @Test
    fun allPriorityCampaignsExcludedFallBackToAutoWhenFallbackEnabled() {
        val excludedPriority = earnableCampaign("campaign-1", "Selected Game")
        val other = earnableCampaign("campaign-2", "Other Game")
        val settings = AppSettings(
            selectedGamePriority = listOf("Selected Game"),
            fallbackToAutoWhenPrioritizedComplete = true,
            excludedCampaignIds = setOf("campaign-1"),
        ).normalized()

        val decision = CampaignPrioritySelector.initialDecision(settings, listOf(excludedPriority, other))

        assertEquals(
            CampaignSelectionMode.AutoFallbackPrioritiesComplete,
            (decision as CampaignCandidateDecision.Try).mode,
        )
        assertEquals(listOf("campaign-2"), decision.candidates.map { it.id })
    }

    @Test
    fun allPriorityCampaignsExcludedIdleClearlyWhenFallbackDisabled() {
        val excludedPriority = earnableCampaign("campaign-1", "Selected Game")
        val other = earnableCampaign("campaign-2", "Other Game")
        val settings = AppSettings(
            selectedGamePriority = listOf("Selected Game"),
            excludedCampaignIds = setOf("campaign-1"),
        ).normalized()

        val decision = CampaignPrioritySelector.initialDecision(settings, listOf(excludedPriority, other))

        assertEquals("Prioritized campaigns are excluded", (decision as CampaignCandidateDecision.Idle).task)
        assertEquals(
            "Auto Mode fallback for unavailable prioritized campaigns is disabled.",
            decision.detail,
        )
    }

    @Test
    fun completeFallbackRequiresAllPrioritizedGamesToBeComplete() {
        val completeFirstPriority = completedCampaign("campaign-1", "First Priority")
        val completeSecondPriority = completedCampaign("campaign-2", "Second Priority")
        val other = earnableCampaign("campaign-3", "Other Game")
        val settings = AppSettings(
            selectedGamePriority = listOf("First Priority", "Second Priority"),
            fallbackToAutoWhenPrioritizedComplete = true,
        ).normalized()

        val decision = CampaignPrioritySelector.initialDecision(
            settings,
            listOf(completeFirstPriority, other, completeSecondPriority),
        )

        assertEquals(
            CampaignSelectionMode.AutoFallbackPrioritiesComplete,
            (decision as CampaignCandidateDecision.Try).mode,
        )
        assertEquals(listOf("campaign-3"), decision.candidates.map { it.id })
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
    fun noChannelPriorityFallbackSkipsExcludedCampaigns() {
        val prioritized = earnableCampaign("campaign-1", "Selected Game")
        val excludedOther = earnableCampaign("campaign-2", "Other Game")
        val settings = AppSettings(
            selectedGamePriority = listOf("Selected Game"),
            fallbackToAutoWhenNoPrioritizedChannel = true,
            excludedCampaignIds = setOf("campaign-2"),
        ).normalized()

        val decision = CampaignPrioritySelector.afterNoPrioritizedChannelDecision(
            settings,
            listOf(prioritized, excludedOther),
        )

        assertEquals(
            "No prioritized games have eligible live channels",
            (decision as CampaignCandidateDecision.Idle).task,
        )
        assertEquals(
            "Auto Mode fallback is enabled, but no other eligible campaign is available.",
            decision.detail,
        )
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
    fun unlinkedGamesAreSkippedWhenSettingIsDisabled() {
        val unlinked = unlinkedCampaign("campaign-1", "Unlinked Game")
        val settings = AppSettings().normalized()

        val decision = CampaignPrioritySelector.initialDecision(settings, listOf(unlinked))

        assertEquals("No available campaign can be mined", (decision as CampaignCandidateDecision.Idle).task)
        assertEquals(emptyList<Campaign>(), CampaignPrioritySelector.candidates(settings, listOf(unlinked)))
    }

    @Test
    fun unlinkedGamesCanBeTriedAfterLinkedAutoCandidates() {
        val linked = earnableCampaign("campaign-1", "Linked Game")
        val unlinked = unlinkedCampaign("campaign-2", "Unlinked Game")
        val settings = AppSettings(allowWatchingUnlinkedGames = true).normalized()

        val initialDecision = CampaignPrioritySelector.initialDecision(
            settings,
            listOf(unlinked, linked),
        )
        val afterLinkedNoChannel = CampaignPrioritySelector.afterNoChannelDecision(
            settings,
            listOf(unlinked, linked),
            CampaignSelectionMode.Auto,
        )

        assertEquals(CampaignSelectionMode.Auto, (initialDecision as CampaignCandidateDecision.Try).mode)
        assertEquals(listOf("campaign-1"), initialDecision.candidates.map { it.id })
        assertEquals(
            CampaignSelectionMode.Unlinked,
            (afterLinkedNoChannel as CampaignCandidateDecision.Try).mode,
        )
        assertEquals(listOf("campaign-2"), afterLinkedNoChannel.candidates.map { it.id })
    }

    @Test
    fun unlinkedFallbackSkipsExcludedCampaigns() {
        val unlinked = unlinkedCampaign("campaign-1", "Unlinked Game")
        val settings = AppSettings(
            allowWatchingUnlinkedGames = true,
            excludedCampaignIds = setOf("campaign-1"),
        ).normalized()

        val decision = CampaignPrioritySelector.initialDecision(settings, listOf(unlinked))

        assertEquals("No available campaign can be mined", (decision as CampaignCandidateDecision.Idle).task)
        assertEquals(emptyList<Campaign>(), CampaignPrioritySelector.unlinkedCandidates(settings, listOf(unlinked)))
    }

    @Test
    fun prioritizedUnlinkedGameCanBeTriedWhenExplicitlyEnabled() {
        val unlinked = unlinkedCampaign("campaign-1", "Priority Game")
        val settings = AppSettings(
            selectedGamePriority = listOf("Priority Game"),
            allowWatchingUnlinkedGames = true,
        ).normalized()

        val decision = CampaignPrioritySelector.initialDecision(settings, listOf(unlinked))

        assertEquals(CampaignSelectionMode.Unlinked, (decision as CampaignCandidateDecision.Try).mode)
        assertEquals(listOf("campaign-1"), decision.candidates.map { it.id })
    }

    @Test
    fun prioritizedUnlinkedGameIsNotTriedWhenSettingIsDisabled() {
        val unlinked = unlinkedCampaign("campaign-1", "Priority Game")
        val settings = AppSettings(
            selectedGamePriority = listOf("Priority Game"),
            allowWatchingUnlinkedGames = false,
        ).normalized()

        val decision = CampaignPrioritySelector.initialDecision(settings, listOf(unlinked))

        assertEquals("No prioritized game can be mined", (decision as CampaignCandidateDecision.Idle).task)
    }

    @Test
    fun noChannelFallbackCanTryPrioritizedUnlinkedWithoutAutoFallback() {
        val linked = earnableCampaign("campaign-1", "Linked Priority")
        val unlinked = unlinkedCampaign("campaign-2", "Unlinked Priority")
        val outsidePriority = unlinkedCampaign("campaign-3", "Outside Priority")
        val settings = AppSettings(
            selectedGamePriority = listOf("Linked Priority", "Unlinked Priority"),
            fallbackToAutoWhenNoPrioritizedChannel = false,
            allowWatchingUnlinkedGames = true,
        ).normalized()

        val decision = CampaignPrioritySelector.afterNoPrioritizedChannelDecision(
            settings,
            listOf(outsidePriority, unlinked, linked),
        )

        assertEquals(CampaignSelectionMode.Unlinked, (decision as CampaignCandidateDecision.Try).mode)
        assertEquals(listOf("campaign-2"), decision.candidates.map { it.id })
    }

    @Test
    fun autoFallbackThenUnlinkedKeepsPriorityOrderWithinUnlinkedCandidates() {
        val linked = earnableCampaign("campaign-1", "Linked Priority")
        val prioritizedUnlinked = unlinkedCampaign("campaign-2", "Unlinked Priority", requiredMinutes = 30)
        val outsidePriority = unlinkedCampaign("campaign-3", "Outside Priority", requiredMinutes = 10)
        val settings = AppSettings(
            selectedGamePriority = listOf("Linked Priority", "Unlinked Priority"),
            fallbackToAutoWhenNoPrioritizedChannel = true,
            allowWatchingUnlinkedGames = true,
        ).normalized()

        val decision = CampaignPrioritySelector.afterNoChannelDecision(
            settings,
            listOf(outsidePriority, prioritizedUnlinked, linked),
            CampaignSelectionMode.AutoFallbackNoPrioritizedChannel,
        )

        assertEquals(CampaignSelectionMode.Unlinked, (decision as CampaignCandidateDecision.Try).mode)
        assertEquals(listOf("campaign-2", "campaign-3"), decision.candidates.map { it.id })
    }

    @Test
    fun candidateOrderIsPrioritizedThenLinkedThenUnlinked() {
        val prioritized = earnableCampaign("campaign-1", "Priority Game")
        val linked = earnableCampaign("campaign-2", "Linked Game")
        val unlinked = unlinkedCampaign("campaign-3", "Unlinked Game")
        val settings = AppSettings(
            selectedGamePriority = listOf("Priority Game"),
            fallbackToAutoWhenNoPrioritizedChannel = true,
            allowWatchingUnlinkedGames = true,
        ).normalized()
        val campaigns = listOf(unlinked, linked, prioritized)

        val initialDecision = CampaignPrioritySelector.initialDecision(settings, campaigns)
        val afterPriorityNoChannel = CampaignPrioritySelector.afterNoChannelDecision(
            settings,
            campaigns,
            CampaignSelectionMode.Prioritized,
        )
        val afterLinkedNoChannel = CampaignPrioritySelector.afterNoChannelDecision(
            settings,
            campaigns,
            CampaignSelectionMode.AutoFallbackNoPrioritizedChannel,
        )

        assertEquals(
            CampaignSelectionMode.Prioritized,
            (initialDecision as CampaignCandidateDecision.Try).mode,
        )
        assertEquals(listOf("campaign-1"), initialDecision.candidates.map { it.id })
        assertEquals(
            CampaignSelectionMode.AutoFallbackNoPrioritizedChannel,
            (afterPriorityNoChannel as CampaignCandidateDecision.Try).mode,
        )
        assertEquals(listOf("campaign-2"), afterPriorityNoChannel.candidates.map { it.id })
        assertEquals(
            CampaignSelectionMode.Unlinked,
            (afterLinkedNoChannel as CampaignCandidateDecision.Try).mode,
        )
        assertEquals(listOf("campaign-3"), afterLinkedNoChannel.candidates.map { it.id })
    }

    @Test
    fun unlinkedGameWithNoProgressIsSkippedAfterCheckWindow() {
        val now = Instant.parse("2026-06-04T12:00:00Z")
        val settings = AppSettings(watchIntervalSeconds = 20).normalized()
        val campaign = unlinkedCampaign("campaign-1", "Unlinked Game", currentMinutes = 5)
        val probe = UnlinkedProgressProbe.start(campaign, settings, now)

        val result = probe.observe(campaign, now.plus(Duration.ofMinutes(2)))

        assertTrue(result is UnlinkedProgressProbeResult.Skip)
    }

    @Test
    fun unlinkedGameWithProgressContinuesWatching() {
        val now = Instant.parse("2026-06-04T12:00:00Z")
        val settings = AppSettings(watchIntervalSeconds = 20).normalized()
        val campaign = unlinkedCampaign("campaign-1", "Unlinked Game", currentMinutes = 5)
        val progressed = unlinkedCampaign("campaign-1", "Unlinked Game", currentMinutes = 6)
        val probe = UnlinkedProgressProbe.start(campaign, settings, now)

        val result = probe.observe(progressed, now.plus(Duration.ofMinutes(2)))

        assertTrue(result is UnlinkedProgressProbeResult.Continue)
        val continued = result as UnlinkedProgressProbeResult.Continue
        assertEquals(true, continued.progressDetectedNow)
        assertEquals(true, continued.probe.progressDetected)
        val laterResult = continued.probe.observe(progressed, now.plus(Duration.ofMinutes(10)))
        assertTrue(laterResult is UnlinkedProgressProbeResult.Continue)
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

private fun unlinkedCampaign(
    id: String,
    gameName: String,
    currentMinutes: Int = 0,
    requiredMinutes: Int = 60,
): Campaign =
    earnableCampaign(
        id = id,
        gameName = gameName,
        currentMinutes = currentMinutes,
        requiredMinutes = requiredMinutes,
    ).copy(
        linked = false,
        linkStatusKnown = true,
        linkUrl = "https://example.test/link",
    )
