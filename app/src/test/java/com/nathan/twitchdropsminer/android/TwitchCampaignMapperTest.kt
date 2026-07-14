package com.nathan.twitchdropsminer.android

import com.nathan.twitchdropsminer.android.data.twitch.TwitchCampaignMapper
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitchCampaignMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun claimedBenefitIdDuringDropWindowMarksDropClaimed() {
        val campaign = TwitchCampaignMapper.campaignFromJson(
            campaignJson(),
            mapOf("benefit-1" to Instant.parse("2026-06-03T01:00:00Z")),
        ) ?: error("campaign should parse")

        val drop = campaign.drops.single()

        assertTrue(drop.isClaimed)
        assertEquals(30, drop.currentMinutes)
        assertEquals(1f, drop.progress, 0.001f)
        assertEquals("benefit-1", drop.rewards.single().id)
        assertEquals("BADGE", drop.rewards.single().type)
        assertTrue(campaign.linkStatusKnown)
    }

    @Test
    fun claimedBenefitOutsideDropWindowDoesNotMarkDropClaimed() {
        val campaign = TwitchCampaignMapper.campaignFromJson(
            campaignJson(),
            mapOf("benefit-1" to Instant.parse("2026-06-04T01:00:00Z")),
        ) ?: error("campaign should parse")

        assertFalse(campaign.drops.single().isClaimed)
        assertEquals(0, campaign.drops.single().currentMinutes)
    }

    @Test
    fun missingSelfLinkFieldMarksStatusUnknown() {
        val campaign = TwitchCampaignMapper.campaignFromJson(
            campaignJson(includeSelf = false),
            emptyMap(),
        ) ?: error("campaign should parse")

        assertFalse(campaign.linked)
        assertFalse(campaign.linkStatusKnown)
    }

    @Test
    fun campaignMapperNormalizesUnorderedDropsByRequiredTime() {
        val campaign = TwitchCampaignMapper.campaignFromJson(
            json.parseToJsonElement(
                """
                {
                  "id": "campaign-1",
                  "name": "Campaign",
                  "status": "ACTIVE",
                  "game": {"id": "game-1", "displayName": "Game"},
                  "self": {"isAccountConnected": true},
                  "timeBasedDrops": [
                    {"id": "long", "name": "Long", "requiredMinutesWatched": 120, "preconditionDrops": []},
                    {"id": "short", "name": "Short", "requiredMinutesWatched": 15, "preconditionDrops": []},
                    {"id": "medium", "name": "Medium", "requiredMinutesWatched": 60, "preconditionDrops": []}
                  ],
                  "allow": {"channels": []}
                }
                """.trimIndent(),
            ).jsonObject,
            emptyMap(),
        ) ?: error("campaign should parse")

        assertEquals(listOf("short", "medium", "long"), campaign.drops.map { drop -> drop.id })
    }

    private fun campaignJson(includeSelf: Boolean = true) = json.parseToJsonElement(
        """
        {
          "id": "campaign-1",
          "name": "Campaign",
          "status": "ACTIVE",
          "startAt": "2026-06-03T00:00:00Z",
          "endAt": "2026-06-03T12:00:00Z",
          "game": {
            "id": "game-1",
            "displayName": "Game",
            "boxArtURL": "https://static-cdn.jtvnw.net/game.jpg"
          },
          ${if (includeSelf) """"self": {"isAccountConnected": true},""" else ""}
          "timeBasedDrops": [
            {
              "id": "drop-1",
              "name": "Drop",
              "startAt": "2026-06-03T00:00:00Z",
              "endAt": "2026-06-03T12:00:00Z",
              "requiredMinutesWatched": 30,
              "benefitEdges": [
                {
                  "benefit": {
                    "id": "benefit-1",
                    "name": "Badge",
                    "distributionType": "BADGE",
                    "imageAssetURL": "https://static-cdn.jtvnw.net/reward.png"
                  }
                }
              ],
              "preconditionDrops": []
            }
          ],
          "allow": {"channels": []}
        }
        """.trimIndent(),
    ).jsonObject
}
