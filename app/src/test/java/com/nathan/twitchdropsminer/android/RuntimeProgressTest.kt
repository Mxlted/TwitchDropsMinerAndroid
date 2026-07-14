package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.inEarningOrder
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.runtime.TwitchProgressUpdate
import com.nathan.twitchdropsminer.android.runtime.applyTwitchProgress
import org.junit.Assert.assertEquals
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
    fun delayedTwitchProgressDoesNotRegressCurrentSessionMinutes() {
        val campaign = linkedCampaign(
            "campaign-1",
            "Game",
            currentMinutes = 20,
            requiredMinutes = 60,
        )

        val result = campaign.applyTwitchProgress(
            CurrentDropProgress(dropId = "campaign-1-drop", currentMinutes = 12),
        )

        val updated = result as TwitchProgressUpdate.Updated
        assertEquals(20, updated.campaign.drops.first().currentMinutes)
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
    fun activeDropDoesNotSkipAnUnclaimedPrerequisite() {
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

        assertEquals("prerequisite", campaign.activeDrop(preferredDropId = "dependent")?.id)

        val claimedPrerequisite = prerequisite.copy(
            currentMinutes = 30,
            progress = 1f,
            isClaimed = true,
        )
        assertEquals(
            "dependent",
            campaign.copy(drops = listOf(dependent, claimedPrerequisite)).activeDrop()?.id,
        )
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
    preconditionDropIds: List<String> = emptyList(),
): CampaignDrop =
    CampaignDrop(
        id = id,
        name = id,
        currentMinutes = currentMinutes,
        requiredMinutes = requiredMinutes,
        progress = storedProgress,
        isClaimed = false,
        canClaim = false,
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
