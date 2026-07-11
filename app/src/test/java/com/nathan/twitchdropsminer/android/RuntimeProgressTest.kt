package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
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

}

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
