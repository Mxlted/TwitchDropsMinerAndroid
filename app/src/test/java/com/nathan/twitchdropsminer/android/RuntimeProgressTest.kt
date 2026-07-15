package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.inEarningOrder
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.runtime.TwitchProgressUpdate
import com.nathan.twitchdropsminer.android.runtime.applyTwitchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProgressTest {
    @Test
    fun twitchProgressUpdatesMatchingDrop() {
        val campaign = linkedCampaign("campaign-1", "Game", currentMinutes = 5, requiredMinutes = 60)

        val result = campaign.applyTwitchProgress(
            CurrentDropProgress(dropId = "campaign-1-drop", currentMinutes = 12),
        )

        val updated = result as TwitchProgressUpdate.Updated
        val drop = updated.campaign.drops.first()
        assertEquals(12, drop.currentMinutes)
        assertEquals(false, drop.canClaim)
    }

    @Test
    fun twitchProgressForCompletedMinutesMarksClaimable() {
        val campaign = linkedCampaign("campaign-1", "Game", currentMinutes = 50, requiredMinutes = 60)

        val result = campaign.applyTwitchProgress(
            CurrentDropProgress(dropId = "campaign-1-drop", currentMinutes = 75),
        )

        val updated = result as TwitchProgressUpdate.Updated
        val drop = updated.campaign.drops.first()
        // Reported minutes are coerced to the requirement and the drop becomes claimable.
        assertEquals(60, drop.currentMinutes)
        assertTrue(drop.canClaim)
    }

    @Test
    fun twitchProgressForUnexpectedDropIsSafeNoOp() {
        val campaign = linkedCampaign("campaign-1", "Game", currentMinutes = 5, requiredMinutes = 60)

        val result = campaign.applyTwitchProgress(
            CurrentDropProgress(dropId = "some-other-drop", currentMinutes = 40),
        )

        assertTrue(result is TwitchProgressUpdate.UnexpectedDrop)
        // The original campaign progress is untouched.
        assertEquals(5, campaign.drops.first().currentMinutes)
    }

    @Test
    fun twitchProgressReplacesStaleCachedMinutes() {
        val campaign = linkedCampaign(
            "campaign-1",
            "Game",
            currentMinutes = 60,
            requiredMinutes = 60,
        ).let { cached ->
            cached.copy(drops = cached.drops.map { drop -> drop.copy(canClaim = true) })
        }

        val result = campaign.applyTwitchProgress(
            CurrentDropProgress(dropId = "campaign-1-drop", currentMinutes = 12),
        )

        val updated = result as TwitchProgressUpdate.Updated
        assertEquals(12, updated.campaign.drops.first().currentMinutes)
        assertEquals(0.2f, updated.campaign.drops.first().progressFraction, 0.001f)
        assertFalse(updated.campaign.drops.first().canClaim)
    }

    @Test
    fun dropsAreOrderedByRequiredWatchTimeInsteadOfApiArrayOrder() {
        val drops = listOf(
            drop("long", requiredMinutes = 120),
            drop("short", requiredMinutes = 15),
            drop("medium", requiredMinutes = 60),
        )

        assertEquals(
            listOf("short", "medium", "long"),
            drops.inEarningOrder().map { campaignDrop -> campaignDrop.id },
        )
    }

    @Test
    fun dropOrderingKeepsPrerequisitesAheadOfDependents() {
        val drops = listOf(
            drop("dependent", requiredMinutes = 10, preconditionDropIds = listOf("prerequisite")),
            drop("prerequisite", requiredMinutes = 30),
            drop("independent", requiredMinutes = 20),
        )

        assertEquals(
            listOf("independent", "prerequisite", "dependent"),
            drops.inEarningOrder().map { campaignDrop -> campaignDrop.id },
        )
    }

    @Test
    fun activeDropUsesTheDropTwitchReportsAsCurrentlyProgressing() {
        val campaign = linkedCampaign("campaign-1", "Game").copy(
            totalDrops = 2,
            drops = listOf(
                drop("short", currentMinutes = 0, requiredMinutes = 15),
                drop("reported", currentMinutes = 20, requiredMinutes = 60),
            ),
        )

        assertEquals("reported", campaign.activeDrop(preferredDropId = "reported")?.id)
        assertEquals("reported", campaign.activeDrop()?.id)
    }

    @Test
    fun activeDropUsesPrerequisitesUnlessTwitchReportsTheDependentAsActive() {
        val prerequisite = drop("prerequisite", requiredMinutes = 30)
        val dependent = drop(
            "dependent",
            requiredMinutes = 10,
            preconditionDropIds = listOf("prerequisite"),
        )
        val campaign = linkedCampaign("campaign-1", "Game").copy(
            totalDrops = 2,
            drops = listOf(dependent, prerequisite),
        )

        assertEquals("prerequisite", campaign.activeDrop()?.id)
        assertEquals("dependent", campaign.activeDrop(preferredDropId = "dependent")?.id)
        assertFalse(campaign.isDropUnlocked(dependent))

        val claimedPrerequisite = prerequisite.copy(
            currentMinutes = 30,
            progress = 1f,
            isClaimed = true,
        )
        assertEquals(
            "dependent",
            campaign.copy(drops = listOf(dependent, claimedPrerequisite)).activeDrop()?.id,
        )
        assertTrue(
            campaign.copy(drops = listOf(dependent, claimedPrerequisite)).isDropUnlocked(dependent),
        )
    }

    @Test
    fun unknownPrerequisiteDoesNotBlockAVisibleDrop() {
        val dependent = drop(
            "dependent",
            requiredMinutes = 10,
            preconditionDropIds = listOf("drop-not-returned-by-twitch"),
        )
        val campaign = linkedCampaign("campaign-1", "Game").copy(
            drops = listOf(dependent),
        )

        assertTrue(campaign.isDropUnlocked(dependent))
        assertEquals("dependent", campaign.activeDrop()?.id)
    }

    @Test
    fun dependentCompletedDropIsNotClaimableUntilItsKnownPrerequisiteIsClaimed() {
        val prerequisite = drop(
            "prerequisite",
            currentMinutes = 30,
            requiredMinutes = 30,
            canClaim = true,
        )
        val dependent = drop(
            "dependent",
            currentMinutes = 10,
            requiredMinutes = 10,
            canClaim = true,
            preconditionDropIds = listOf("prerequisite"),
        )
        val campaign = linkedCampaign("campaign-1", "Game").copy(
            drops = listOf(dependent, prerequisite),
        )

        assertEquals(
            listOf("prerequisite"),
            campaign.claimableDropsInEarningOrder().map { drop -> drop.id },
        )

        val afterPrerequisiteClaim = campaign.copy(
            drops = listOf(dependent, prerequisite.copy(isClaimed = true, canClaim = false)),
        )
        assertEquals(
            listOf("dependent"),
            afterPrerequisiteClaim.claimableDropsInEarningOrder().map { drop -> drop.id },
        )
    }

    @Test
    fun campaignProgressWeightsTheSameWatchedAndRequiredMinutesShownByTheUi() {
        val campaign = linkedCampaign("campaign-1", "Game").copy(
            drops = listOf(
                drop("long", currentMinutes = 60, requiredMinutes = 120),
                drop("short", currentMinutes = 15, requiredMinutes = 15),
            ),
        )

        assertEquals(75f / 135f, campaign.progress, 0.001f)
    }

    @Test
    fun zeroRequirementDropsDoNotInflateAggregateProgress() {
        val campaign = linkedCampaign("campaign-1", "Game").copy(
            drops = listOf(
                drop("invalid", currentMinutes = 50, requiredMinutes = 0),
                drop("normal", currentMinutes = 0, requiredMinutes = 10),
            ),
        )

        assertEquals(0f, campaign.progress, 0.001f)
    }

    @Test
    fun displayedProgressIsDerivedFromWatchedMinutesWhenStoredFractionIsStale() {
        val drop = drop(
            id = "drop",
            currentMinutes = 30,
            requiredMinutes = 60,
            storedProgress = 0f,
        )

        assertEquals(0.5f, drop.progressFraction, 0.001f)
        assertEquals(30, drop.watchedMinutes)
        assertEquals(30, drop.remainingMinutes)
    }

}

private fun drop(
    id: String,
    currentMinutes: Int = 0,
    requiredMinutes: Int,
    storedProgress: Float = if (requiredMinutes <= 0) {
        0f
    } else {
        currentMinutes.toFloat() / requiredMinutes.toFloat()
    },
    isClaimed: Boolean = false,
    canClaim: Boolean = false,
    preconditionDropIds: List<String> = emptyList(),
): CampaignDrop =
    CampaignDrop(
        id = id,
        name = id,
        currentMinutes = currentMinutes,
        requiredMinutes = requiredMinutes,
        progress = storedProgress,
        isClaimed = isClaimed,
        canClaim = canClaim,
        rewards = emptyList(),
        preconditionDropIds = preconditionDropIds,
    )

private fun linkedCampaign(
    id: String,
    gameName: String,
    currentMinutes: Int = 0,
    requiredMinutes: Int = 60,
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
                canClaim = false,
                rewards = emptyList(),
            ),
        ),
    )
